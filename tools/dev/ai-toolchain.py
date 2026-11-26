#!/usr/bin/env python3
"""111 按需工具链入口：直接消费 777 已验收且固定摘要的组件，不覆盖本项目构建基线。"""
import argparse
import hashlib
import json
import os
import pathlib
import platform
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import zipfile

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parents[1]
LOCK = json.loads((HERE / "toolchain-lock.json").read_text())
COMPONENTS = LOCK["shared"]
import host_components as host

def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()

def digest_stream(stream):
    h = hashlib.sha256()
    for block in iter(lambda: stream.read(1024 * 1024), b""):
        h.update(block)
    return h.hexdigest()

def require(condition, message):
    if not condition:
        raise ValueError(message)

def verify_zip(path, component, spec):
    require(path.is_file() and path.stat().st_size == spec["size"], "产物大小错误: " + str(path))
    require(sha256(path) == spec["sha256"], "产物 SHA-256 不匹配: " + str(path))
    with zipfile.ZipFile(path) as z:
        require(sorted(z.namelist()) == ["SHA256SUMS", "component.env", "payload.tar.gz"], "产物布局错误")
        lines = z.read("component.env").decode().splitlines()
        meta = dict(line.split("=", 1) for line in lines)
        require(len(meta) == len(lines), "重复产物字段")
        expected = {"ARTIFACT_SCHEMA": "4", "ARTIFACT_COMPONENT": component,
                    "ARTIFACT_ARCH": "x64", "ARTIFACT_HEAD_SHA": LOCK["source"]["head"],
                    "ARTIFACT_PART": "1", "ARTIFACT_PARTS": "1"}
        require(all(meta.get(k) == v for k, v in expected.items()), "产物来源/架构/组件不匹配")
        sums = dict((name, digest) for digest, name in
                    (line.split("  ", 1) for line in z.read("SHA256SUMS").decode().splitlines()))
        require(set(sums) == {"component.env", "payload.tar.gz"}, "内部摘要清单不完整")
        for name, digest in sums.items():
            with z.open(name) as stream:
                require(digest_stream(stream) == digest, "内部摘要错误: " + name)

def component_dir(root, name, spec):
    return root / name / spec["sha256"]

def check_installed(root, name, spec):
    dest = component_dir(root, name, spec)
    marker = dest / ".source.json"
    if not marker.is_file() or marker.read_text() != json.dumps(spec, sort_keys=True):
        return False
    if spec["probe"]:
        binary, *args = spec["probe"]
        try:
            result = subprocess.run([str(dest / binary), *args], check=True,
                                    text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=30)
            return re.search(r"(?<![\d.])" + re.escape(spec["version"]) + r"(?![\d.])", result.stdout) is not None
        except (OSError, subprocess.SubprocessError):
            return False
    cache = dest / "runtime-cache"
    return all((cache / ("termux-" + name) / "index-aarch64/InRelease").is_file()
               for name in ("git", "node", "python"))

def install(root, name, archive):
    spec = COMPONENTS[name]
    verify_zip(archive, name, spec)
    dest = component_dir(root, name, spec)
    dest.parent.mkdir(parents=True, exist_ok=True)
    if check_installed(root, name, spec):
        return
    with tempfile.TemporaryDirectory(prefix=".install-", dir=dest.parent) as temp:
        stage = pathlib.Path(temp) / "component"
        stage.mkdir()
        payload = pathlib.Path(temp) / "payload.tar.gz"
        with zipfile.ZipFile(archive) as z, z.open("payload.tar.gz") as stream, payload.open("wb") as out:
            shutil.copyfileobj(stream, out)
        with tarfile.open(payload, "r:gz") as tar:
            members = tar.getmembers()
            require(len(members) <= 20000 and sum(m.size for m in members) <= 1024**3,
                    "组件解压范围超限")
            seen = set()
            for member in members:
                path = pathlib.PurePosixPath(member.name)
                require(not path.is_absolute() and ".." not in path.parts and
                        (not path.parts or path.parts[0] == spec["root"]), "组件路径越界")
                require(path not in seen, "重复组件路径")
                seen.add(path)
                require(member.isfile() or member.isdir() or member.islnk() or member.issym(),
                        "组件含不支持的特殊文件")
            # Python data filter blocks traversal and links escaping the stage.
            tar.extractall(stage, members=members, filter="data")
        (stage / ".source.json").write_text(json.dumps(spec, sort_keys=True))
        # Never replace an unrelated version or write into a host SDK directory.
        if dest.exists():
            require(not dest.is_symlink(), "拒绝符号链接组件目录")
            shutil.rmtree(dest)
        stage.rename(dest)
    require(check_installed(root, name, spec), "组件安装后真实版本探测失败: " + name)

def find_archive(directory, name, spec):
    candidates = (directory / (spec["name"] + ".zip"), directory / ("777-" + name + ".zip"))
    found = [p for p in candidates if p.is_file()]
    require(len(found) == 1, "请提供唯一固定产物: " + spec["name"] + ".zip")
    return found[0]

def host_environment(root):
    env = os.environ.copy()
    missing = [n for n, s in COMPONENTS.items() if not check_installed(root, n, s)]
    require(not missing, "缺少已核验组件: " + ", ".join(missing))
    env["PATH"] = os.pathsep.join([str(component_dir(root, "node", COMPONENTS["node"]) / "node-current/bin"),
                                   str(component_dir(root, "actionlint", COMPONENTS["actionlint"]) / "bin"),
                                   env.get("PATH", "")])
    env["CODEX_777_RUNTIME_CACHE"] = str(component_dir(root, "runtime-cache", COMPONENTS["runtime-cache"]) / "runtime-cache")
    return env

def project_check():
    p = LOCK["project"]
    files = {
        "android-app/build.gradle.kts": [p["agp"], p["kotlin"]],
        "android-app/app/build.gradle.kts": [f"compileSdk = {p['compileSdk']}", f"minSdk = {p['minSdk']}",
                                              f"targetSdk = {p['targetSdk']}", p["composeBom"]],
        "android-app/gradle/wrapper/gradle-wrapper.properties": [f"gradle-{p['gradle']}-bin.zip", p["gradleDistributionSha256"]],
        "upstream/codex/codex-rs/rust-toolchain.toml": [p["rust"]],
        "android-app/tools/prepare_android_v8.sh": [p["v8"], p["v8Commit"]],
        ".github/workflows/ci.yml": [p["buildTools"], p["ndk"], p["jsonschema"]],
        "android-app/tools/device-matrix.json": ['"system_image_api": "' + p["deviceImage"] + '"'],
    }
    for relative, terms in files.items():
        text = (REPO / relative).read_text()
        for term in terms:
            require(term in text, "111 工具链版本漂移: " + relative + " / " + term)
    return [p for p in ("android-app/app/gradle.lockfile", "android-app/gradle/verification-metadata.xml")
            if not (REPO / p).is_file()]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=pathlib.Path,
                        default=pathlib.Path.home() / ".local/share/111-dev/777")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("plan")
    sub.add_parser("check")
    install_host = sub.add_parser("host-install")
    host_source = install_host.add_mutually_exclusive_group(required=True)
    host_source.add_argument("--artifacts-dir", type=pathlib.Path)
    host_source.add_argument("--run-id", type=int)
    install_host.add_argument("--pins-dir", type=pathlib.Path)
    install_host.add_argument("--source-sha")
    install_host.add_argument("--components", nargs="+", choices=host.HOST, default=list(host.HOST))
    install_host.add_argument("--repair", action="store_true")
    host_env = sub.add_parser("host-env")
    host_env.add_argument("--components", nargs="+", choices=host.HOST, default=list(host.HOST))
    cargo = sub.add_parser("cargo")
    cargo.add_argument("args", nargs=argparse.REMAINDER)
    shared = sub.add_parser("shared-install")
    shared.add_argument("--artifacts-dir", type=pathlib.Path, required=True)
    shared.add_argument("--components", nargs="+", choices=sorted(COMPONENTS), default=list(COMPONENTS))
    shared.add_argument("--download", action="store_true")
    boot = sub.add_parser("bootstrap")
    boot.add_argument("--artifacts-dir", type=pathlib.Path, required=True)
    run = sub.add_parser("run")
    run.add_argument("args", nargs=argparse.REMAINDER)
    gradle = sub.add_parser("gradle")
    gradle.add_argument("args", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    require(platform.system() == "Linux" and platform.machine() in ("x86_64", "amd64"),
            "777 当前组件仅适用于 Linux/WSL x64")
    root = args.root.resolve()
    require(root != pathlib.Path("/") and root != pathlib.Path.home(), "工具链根目录不安全")
    if args.command == "host-install":
        if args.run_id is not None:
            require(not args.pins_dir and not args.source_sha, "远端安装仅接受验收运行 ID")
            result = host.install_run(root, args.run_id, args.components, args.repair)
        else:
            require(args.pins_dir and args.source_sha, "本地安装必须明确受信摘要目录及生产提交")
            result = host.install(root, args.artifacts_dir, args.pins_dir, args.source_sha, args.components, args.repair)
        print(json.dumps(result, indent=2))
        return 0
    if args.command == "host-env":
        print(json.dumps(host.environment(root, args.components), indent=2))
        return 0
    if args.command == "shared-install":
        args.artifacts_dir.mkdir(parents=True, exist_ok=True)
        for name in args.components:
            spec = COMPONENTS[name]
            if not check_installed(root, name, spec):
                expected = args.artifacts_dir / (spec["name"] + ".zip")
                alternate = args.artifacts_dir / ("777-" + name + ".zip")
                if args.download and not expected.is_file() and not alternate.is_file():
                    partial = expected.with_suffix(".partial")
                    try:
                        with partial.open("wb") as output:
                            subprocess.run(["gh", "api", f"repos/{LOCK['source']['repository']}/actions/artifacts/{spec['id']}/zip"], stdout=output, check=True)
                        verify_zip(partial, name, spec)
                        partial.replace(expected)
                    finally:
                        partial.unlink(missing_ok=True)
                install(root, name, find_archive(args.artifacts_dir, name, spec))
            require(check_installed(root, name, spec), "共享组件未通过校验: " + name)
        print(json.dumps({name: str(component_dir(root, name, COMPONENTS[name]) / COMPONENTS[name]["root"]) for name in args.components}, indent=2))
        return 0
    missing_locks = project_check()
    if args.command == "bootstrap":
        for name, spec in COMPONENTS.items():
            if not check_installed(root, name, spec):
                install(root, name, find_archive(args.artifacts_dir, name, spec))
    if args.command in ("plan", "check", "bootstrap"):
        state = {"source": LOCK["source"], "shared": {name: {"ready": check_installed(root, name, spec),
                "artifactId": spec["id"], "artifactName": spec["name"], "sha256": spec["sha256"]}
                for name, spec in COMPONENTS.items()},
                "projectVersions": LOCK["project"], "missingAndroidLocks": missing_locks, "hostInstalled": host.selected(root)}
        print(json.dumps(state, ensure_ascii=False, indent=2))
        return 1 if args.command != "plan" and (missing_locks or not all(v["ready"] for v in state["shared"].values())) else 0
    command = args.args[1:] if args.args and args.args[0] == "--" else args.args
    if args.command == "gradle":
        require(not missing_locks, "111 Gradle 依赖锁/校验元数据缺失: " + ", ".join(missing_locks))
        require(not any(x.startswith("--dependency-verification") or x in ("--write-locks", "--write-verification-metadata") for x in command),
                "依赖锁更新须独立审查；构建命令固定严格校验")
        env = os.environ | host.environment(root, host.ANDROID)
        command = [str(REPO / "android-app/gradlew"), "--offline", "--dependency-verification", "strict", *command]
        return subprocess.call(command, cwd=REPO / "android-app", env=env)
    if args.command == "cargo":
        require(command and command[0] in ("build","check","test","fetch","metadata"), "请指定支持锁定依赖的 Cargo 子命令")
        env = os.environ | host.environment(root, host.RUST)
        binary = pathlib.Path(env["RUSTC"]).with_name("cargo")
        return subprocess.call([str(binary), "--offline", command[0], "--locked", *command[1:]],
                               cwd=REPO / "upstream/codex/codex-rs", env=env)
    require(command, "缺少运行命令")
    return subprocess.call(command, cwd=REPO, env=host_environment(root))

if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, OSError, subprocess.SubprocessError, zipfile.BadZipFile, tarfile.TarError) as exc:
        print(str(exc), file=sys.stderr)
        sys.exit(2)
