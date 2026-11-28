#!/usr/bin/env python3
"""Lock a real upstream release baseline for an Android source snapshot."""
import argparse
import re
import subprocess
import tomllib
from pathlib import Path

TAG = re.compile(r"rust-v(\d+\.\d+\.\d+(?:-alpha\.\d+(?:\.\d+)?)?)")
SHA = re.compile(r"[0-9a-f]{40}")
MANIFEST = "codex-rs/Cargo.toml"

def git(repo, *args):
    return subprocess.check_output(["git", "-C", str(repo), *args], text=True).strip()

def ancestor(repo, base, head):
    result = subprocess.run(["git", "-C", str(repo), "merge-base", "--is-ancestor", base, head])
    if result.returncode not in (0, 1):
        raise RuntimeError("上游提交祖先关系核验失败")
    return result.returncode == 0

def resolve(repo, snapshot):
    cutoff = int(git(repo, "show", "-s", "--format=%ct", snapshot))
    tags = git(repo, "for-each-ref", "--sort=-creatordate", "--format=%(refname:short)", "refs/tags/rust-v*").splitlines()
    for tag in tags:
        match = TAG.fullmatch(tag)
        if not match:
            continue
        release = git(repo, "rev-parse", tag + "^{commit}")
        if int(git(repo, "show", "-s", "--format=%ct", release)) > cutoff:
            continue
        parents = git(repo, "show", "-s", "--format=%P", release).split()
        if ancestor(repo, release, snapshot):
            base = release
        elif len(parents) == 1 and ancestor(repo, parents[0], snapshot):
            if git(repo, "diff", "--name-only", parents[0], release).splitlines() != [MANIFEST]:
                continue
            before = tomllib.loads(git(repo, "show", parents[0] + ":" + MANIFEST))
            after = tomllib.loads(git(repo, "show", release + ":" + MANIFEST))
            before["workspace"]["package"]["version"] = after["workspace"]["package"]["version"]
            if before != after:
                continue
            base = parents[0]
        else:
            continue
        manifest = tomllib.loads(git(repo, "show", release + ":" + MANIFEST))
        if manifest["workspace"]["package"]["version"] != match.group(1):
            continue
        return "\n".join((match.group(1), snapshot, release, base)) + "\n"
    raise RuntimeError("没有找到与锁定源码有可验证继承关系的官方发行版本")

def validate(lock, marker):
    lines = lock.read_text(encoding="utf-8").splitlines()
    if len(lines) != 4 or not TAG.fullmatch("rust-v" + lines[0]) or lines[0].split("-")[0] == "0.0.0":
        raise ValueError("官方模型目录版本记录无效")
    if not all(SHA.fullmatch(line) for line in lines[1:]):
        raise ValueError("官方发行版本的提交记录无效")
    if lines[1] != marker.read_text(encoding="utf-8").splitlines()[0]:
        raise ValueError("模型目录版本记录与锁定源码不一致")
    return lines[0]

def stamp_workspace(manifest, version):
    """Apply the verified release baseline to the same Cargo version used by every crate."""
    source = manifest.read_text(encoding="utf-8")
    before = tomllib.loads(source)
    current = before["workspace"]["package"]["version"]
    if current == version:
        return
    if current != "0.0.0":
        raise ValueError(f"源码版本 {current} 与锁定发行版本 {version} 不一致")
    pattern = r'(?m)(^\[workspace\.package\][ \t]*\n(?:(?!\[)[^\n]*\n)*?^version[ \t]*=[ \t]*")0\.0\.0(")'
    stamped, count = re.subn(pattern, lambda m: m[1] + version + m[2], source)
    before["workspace"]["package"]["version"] = version
    if count != 1 or tomllib.loads(stamped) != before:
        raise ValueError("工作区版本写入失败或改动了其他清单字段")
    manifest.write_text(stamped, encoding="utf-8")

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--lock", type=Path, required=True)
    parser.add_argument("--marker", type=Path, required=True)
    parser.add_argument("--upstream", type=Path)
    parser.add_argument("--stamp-workspace", type=Path)
    args = parser.parse_args()
    if args.upstream:
        snapshot = args.marker.read_text(encoding="utf-8").splitlines()[0]
        args.lock.write_text(resolve(args.upstream, snapshot), encoding="utf-8")
    version = validate(args.lock, args.marker)
    if args.stamp_workspace:
        stamp_workspace(args.stamp_workspace, version)
    print(version)

if __name__ == "__main__":
    main()
