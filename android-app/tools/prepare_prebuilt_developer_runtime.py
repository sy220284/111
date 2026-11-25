#!/usr/bin/env python3
"""Select immutable precompiled Android release payloads, never build external tools."""
import argparse
import hashlib
import json
import lzma
import pathlib
import re
import shutil
import subprocess
import tarfile
import tempfile
import zipfile

DSH = {
    "arm64-v8a": ("AndroidDSH-0.2.0-arm64-v8a.apk", "1b58d871de59da6ddac5dde78107c778a5926dd46748ac55f9953cc4e8a8dcbd"),
    "x86_64": ("AndroidDSH-0.2.0-x86_64.apk", "8489da96aa6b22e2cac53b1ace1bcea8c2219db5050602ff94b9f85be616adcf"),
}
CURL = ("https://github.com/vvb2060/curl-android/releases/download/v8.18.0/tool-release.apk.xz",
        "0aa32165eb4a9cb8a56e13053cd3bc80cccb0c761b4daf9688c3e3667522bb4b")
GIT = ("https://github.com/rmyndharis/VSCodroid/releases/download/v1.11.0/app-release.apk",
       "4f83ff6fd8a70f19ee04ad93861d68522a2936c203f6ec941e9c12c4527051a3")
GIT_MANIFEST = ("https://github.com/rmyndharis/VSCodroid/releases/download/v1.11.0/build-manifest.txt",
                "b5209f3349833aba6bb4824a37cddc8f29b5c9e5709b68e7a26536dbfa75dd3e")
PIP = "26.3"
PIP_SHA = "6c36ae7da8695be67e3cd9f7d87919667ced53f57ce2ede75d89b5912ed2bc4c"
SYSTEM = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libz.so", "libandroid.so",
          "libatomic.so", "libaaudio.so", "libOpenSLES.so", "libmediandk.so",
          "libjnigraphics.so", "libEGL.so", "libGLESv2.so", "libvulkan.so"}

def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()

def fetch(url, sha, directory):
    path = directory / url.rsplit("/", 1)[-1]
    if not path.is_file():
        subprocess.run(["curl", "--fail", "--location", "--retry", "3", "--proto", "=https",
                        "--proto-redir", "=https", url, "-o", str(path)], check=True)
    if digest(path) != sha:
        raise ValueError(f"Digest mismatch: {url}")
    return path

def relative(name):
    path = pathlib.PurePosixPath(name)
    if path.is_absolute() or not path.parts or ".." in path.parts or "\\" in name:
        raise ValueError(f"Unsafe archive path: {name}")
    return path

def copy_zip(archive, name, dest):
    dest.parent.mkdir(parents=True, exist_ok=True)
    with archive.open(name) as source, dest.open("wb") as output:
        shutil.copyfileobj(source, output)

def copy_tar_tree(archive, prefix, dest):
    prefix = prefix.rstrip("/") + "/"
    selected = set()
    for member in archive.getmembers():
        name = member.name.removeprefix("./")
        if not name.startswith(prefix) or name == prefix:
            continue
        target = dest.joinpath(*relative(name[len(prefix):]).parts)
        if target in selected:
            raise ValueError(f"Duplicate archive path: {name}")
        selected.add(target)
        if member.isdir():
            target.mkdir(parents=True, exist_ok=True)
            continue
        if member.islnk():
            if not member.linkname.removeprefix("./").startswith(prefix):
                raise ValueError(f"External hardlink: {name}")
        elif not member.isfile():
            raise ValueError(f"Unexpected archive entry: {name}")
        target.parent.mkdir(parents=True, exist_ok=True)
        with archive.extractfile(member) as source, target.open("wb") as output:
            shutil.copyfileobj(source, output)
    if not selected:
        raise ValueError(f"Missing archive tree: {prefix}")

def needed(path, abi, executable=False):
    def read(*args):
        return subprocess.check_output(["readelf", *args, str(path)], text=True)
    header = read("-h")
    machine = "AArch64" if abi == "arm64-v8a" else "X86-64"
    if "ELF64" not in header or machine.lower() not in header.lower():
        raise ValueError(f"Wrong ELF ABI: {path}")
    program = read("-Wl")
    aligns = re.findall(r"^\s*LOAD\s+.*\s(0x[0-9a-fA-F]+)\s*$", program, re.M)
    if not aligns or min(int(x, 16) for x in aligns) < 16384:
        raise ValueError(f"Not 16 KiB page aligned: {path}")
    if executable and "/system/bin/linker64" not in program:
        raise ValueError(f"Not an Android executable: {path}")
    return set(re.findall(r"Shared library: \[([^]]+)\]", read("-Wd")))

def git_payload(apk, abi, native, data):
    copy_zip(apk, f"lib/{abi}/libgit.so", native / "libcodex_git.so")
    copy_zip(apk, f"lib/{abi}/libgit-remote-curl.so", native / "libcodex_git_remote_http.so")
    names = set(apk.namelist())
    libroot = data / "git/lib"
    libroot.mkdir(parents=True, exist_ok=True)
    queue = [native / "libcodex_git.so", native / "libcodex_git_remote_http.so"]
    seen = set()
    while queue:
        path = queue.pop()
        if path in seen:
            continue
        seen.add(path)
        for dep in sorted(needed(path, abi, path.parent == native)):
            if dep in SYSTEM or (native / dep).is_file():
                continue
            if not re.fullmatch(r"lib[A-Za-z0-9_+.-]+\.so(?:\.[A-Za-z0-9.]+)?", dep):
                raise ValueError(f"Invalid dependency name: {dep}")
            source = "assets/usr/lib/" + dep
            if source not in names:
                raise ValueError(f"Missing Git dependency: {dep}")
            target = libroot / dep
            if not target.exists():
                copy_zip(apk, source, target)
            queue.append(target)
    prefix = "assets/usr/share/git-core/templates/"
    for name in sorted(names):
        if name.startswith(prefix) and not name.endswith("/"):
            copy_zip(apk, name, (data / "git/templates").joinpath(*relative(name[len(prefix):]).parts))
        if name.startswith("assets/usr/share/doc/") and not name.endswith("/"):
            if re.match(r"(?i)^(copyright|copying|licen[cs]e|notice)", name.rsplit("/", 1)[-1]):
                copy_zip(apk, name, (data / "licenses/git-distribution").joinpath(
                    *relative(name[len("assets/usr/share/doc/"):]).parts))
    if not (data / "git/templates").is_dir():
        raise ValueError("Git templates missing")
    if not any((data / "licenses/git-distribution/git").glob("*")):
        raise ValueError("Git upstream notice missing")

def prepare(abi, native, assets, work):
    data = work / "data"
    data.mkdir()
    assets.mkdir(parents=True, exist_ok=True)
    filename, sha = DSH[abi]
    dsh_url = "https://github.com/Windmill12/dsh-for-android/releases/download/v0.2.0/" + filename
    with zipfile.ZipFile(fetch(dsh_url, sha, work)) as apk:
        # The prebuilt npm launcher locates its sibling libnode.so.
        for name in ("libnode.so", "libnpm.so", "libpython3.so"):
            copy_zip(apk, f"lib/{abi}/{name}", native / name)
        if not (native / "libc++_shared.so").is_file():
            copy_zip(apk, f"lib/{abi}/libc++_shared.so", native / "libc++_shared.so")
        with apk.open("assets/python-runtime.pkg") as raw, tarfile.open(fileobj=raw, mode="r:gz") as tar:
            copy_tar_tree(tar, f"{abi}/lib/python3.13", data / "lib/python3.13")
        with apk.open("assets/dsh-runtime.pkg") as raw, tarfile.open(fileobj=raw, mode="r:gz") as tar:
            copy_tar_tree(tar, "node_modules/npm", data / "npm")
    requirements = work / "pip-requirements.txt"
    requirements.write_text(f"pip=={PIP} --hash=sha256:{PIP_SHA}\n")
    subprocess.run(["python3", "-m", "pip", "download", "--require-hashes", "--no-deps",
                    "--only-binary=:all:", "-r", str(requirements), "-d", str(work)], check=True)
    wheel = work / f"pip-{PIP}-py3-none-any.whl"
    if digest(wheel) != PIP_SHA:
        raise ValueError("pip wheel checksum mismatch")
    with zipfile.ZipFile(wheel) as archive:
        for info in archive.infolist():
            if not info.is_dir():
                copy_zip(archive, info.filename, (data / "lib/python3.13/site-packages").joinpath(*relative(info.filename).parts))
    curl_apk = work / "curl.apk"
    with lzma.open(fetch(*CURL, work)) as source, curl_apk.open("wb") as output:
        shutil.copyfileobj(source, output)
    with zipfile.ZipFile(curl_apk) as apk:
        copy_zip(apk, f"lib/{abi}/libcurl.so", native / "libcodex_curl.so")
    provenance = {"abi": abi, "dsh": {"url": dsh_url, "sha256": sha},
                  "curl": {"url": CURL[0], "sha256": CURL[1]},
                  "pip": {"version": PIP, "sha256": PIP_SHA},
                  "gitHttps": abi == "arm64-v8a"}
    if abi == "arm64-v8a":
        with zipfile.ZipFile(fetch(*GIT, work)) as apk:
            git_payload(apk, abi, native, data)
        shutil.copyfile(fetch(*GIT_MANIFEST, work), data / "git-distributor-manifest.txt")
        provenance["git"] = {"url": GIT[0], "sha256": GIT[1], "manifest": GIT_MANIFEST[1]}
        provenance["gitCorrespondingSourceVerified"] = False
    for path in [native / x for x in ("libnode.so", "libnpm.so", "libpython3.so", "libcodex_curl.so")]:
        for dep in needed(path, abi, True) - SYSTEM:
            if not (native / dep).is_file():
                raise ValueError(f"Unresolved runtime dependency {dep}: {path}")
    for path in (data / "lib/python3.13").rglob("*.so"):
        for dep in needed(path, abi) - SYSTEM:
            if not (native / dep).is_file():
                raise ValueError(f"Unresolved Python extension dependency {dep}: {path}")
    for path in ("lib/python3.13/encodings/__init__.py",
                 "lib/python3.13/site-packages/pip/__main__.py", "npm/bin/npm-cli.js"):
        if not (data / path).is_file():
            raise ValueError(f"Incomplete precompiled runtime: {path}")
    out = assets / "development-runtime.zip"
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for path in sorted(data.rglob("*")):
            if path.is_file():
                archive.write(path, path.relative_to(data).as_posix())
    (assets / "development-runtime.sha256").write_text(digest(out) + "\n")
    (assets / "development-runtime.version").write_text("AndroidDSH 0.2.0 / curl 8.18.0 / pip 26.3\n")
    (assets / "development-runtime.provenance.json").write_text(json.dumps(provenance, indent=2) + "\n")
    print(json.dumps(provenance, indent=2))
    print(f"Runtime data archive: {out.stat().st_size} bytes")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("abi", choices=sorted(DSH))
    parser.add_argument("native", type=pathlib.Path)
    parser.add_argument("assets", type=pathlib.Path)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="prebuilt-developer-") as tmp:
        prepare(args.abi, args.native, args.assets, pathlib.Path(tmp))
