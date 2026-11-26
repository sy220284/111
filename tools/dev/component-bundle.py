#!/usr/bin/env python3
"""Independent, content-verified 111 development components. No online installer on consumer."""
import argparse
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import tarfile
import tempfile

PART_SIZE = 350 * 1024 * 1024
ROOT = pathlib.Path(__file__).resolve().parents[2]
NAMES = ("jdk", "android-sdk", "gradle", "rust", "cargo", "wheelhouse", "emulator", "runtime-arm64", "runtime-x86", "v8-arm64", "v8-x86", "uv", "pnpm", "bazel", "llvm", "patcher")

def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()

def require(value, message):
    if not value:
        raise ValueError(message)

def inventory(root):
    entries = []
    for path in sorted(root.rglob("*")):
        rel = path.relative_to(root).as_posix()
        if path.is_symlink():
            target = os.readlink(path)
            require(not pathlib.PurePosixPath(target).is_absolute() and path.resolve().is_relative_to(root.resolve()), "external link: " + rel)
            entries.append([rel, "link", target])
        elif path.is_file():
            entries.append([rel, "file", path.stat().st_size, digest(path), bool(path.stat().st_mode & 0o111)])
        elif path.is_dir():
            entries.append([rel, "dir"])
        else:
            raise ValueError("special file: " + rel)
    return entries

def manifest_digest(entries):
    return hashlib.sha256(json.dumps(entries, separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()

def pack(name, folder, out, head):
    require(folder.is_dir() and not folder.is_symlink(), "component input missing")
    out.mkdir(parents=True, exist_ok=False)
    entries = inventory(folder)
    require(entries, "empty component")
    size = sum(e[2] for e in entries if e[1] == "file")
    require(size <= 32 * 1024**3, "component too large")
    parts = []
    with tempfile.TemporaryDirectory(dir=out.parent) as tmp:
        archive = pathlib.Path(tmp) / "component.tar.gz"
        with tarfile.open(archive, "w:gz", compresslevel=3) as tar:
            for path in sorted(folder.iterdir()):
                tar.add(path, arcname=path.name)
        archive_hash = hashlib.sha256()
        with archive.open("rb") as stream:
            index = 0
            while True:
                part = out / ("payload.%03d" % index)
                written = 0
                part_hash = hashlib.sha256()
                with part.open("wb") as target:
                    while written < PART_SIZE:
                        block = stream.read(min(1024**2, PART_SIZE - written))
                        if not block:
                            break
                        target.write(block)
                        part_hash.update(block)
                        archive_hash.update(block)
                        written += len(block)
                if not written:
                    part.unlink()
                    break
                parts.append({"name": part.name, "size": written, "sha256": part_hash.hexdigest()})
                index += 1
    m = {"schema": 1, "component": name, "repository": "sy220284/111", "head": head,
         "archiveSha256": archive_hash.hexdigest(), "unpackedBytes": size, "treeSha256": manifest_digest(entries), "parts": parts}
    (out / "manifest.json").write_text(json.dumps(m, indent=2) + "\n")
    return digest(out / "manifest.json")

def load_manifest(folder, name, expected):
    require(folder.is_dir() and not folder.is_symlink(), "invalid component directory")
    manifest = folder / "manifest.json"
    require(manifest.is_file() and not manifest.is_symlink(), "invalid manifest file")
    raw = manifest.read_bytes()
    require(hashlib.sha256(raw).hexdigest() == expected, "manifest digest mismatch")
    m = json.loads(raw)
    require(m["schema"] == 1 and m["repository"] == "sy220284/111" and m["component"] == name, "component identity mismatch")
    require(0 < len(m["parts"]) <= 100 and 0 < m["unpackedBytes"] <= 32 * 1024**3, "component bounds")
    require({p.name for p in folder.iterdir()} == {"manifest.json", *(p["name"] for p in m["parts"])}, "unexpected component files")
    for i, spec in enumerate(m["parts"]):
        require(spec["name"] == "payload.%03d" % i and 0 < spec["size"] <= PART_SIZE, "invalid part")
        part = folder / spec["name"]
        require(part.is_file() and not part.is_symlink() and part.stat().st_size == spec["size"], "corrupt part: " + spec["name"])
    return m

def verify_stream(folder, manifest, output=None):
    """Validate each part and the complete archive in one bounded pass.
    When materializing, the exact verified bytes are also the bytes written."""
    archive = hashlib.sha256()
    for spec in manifest["parts"]:
        part = folder / spec["name"]
        require(part.is_file() and not part.is_symlink(), "invalid part: " + spec["name"])
        part_hash = hashlib.sha256()
        count = 0
        with part.open("rb") as source:
            while block := source.read(1024**2):
                count += len(block)
                require(count <= spec["size"], "corrupt part: " + spec["name"])
                part_hash.update(block)
                archive.update(block)
                if output is not None:
                    output.write(block)
        require(count == spec["size"] and part_hash.hexdigest() == spec["sha256"],
                "corrupt part: " + spec["name"])
    require(archive.hexdigest() == manifest["archiveSha256"], "archive digest mismatch")

def load(folder, name, expected):
    m = load_manifest(folder, name, expected)
    verify_stream(folder, m)
    return m

def install(name, folder, root, expected):
    m = load_manifest(folder, name, expected)
    parent = root / name
    parent.mkdir(parents=True, exist_ok=True)
    dest = parent / expected
    if dest.is_dir() and not dest.is_symlink() and manifest_digest(inventory(dest)) == m["treeSha256"]:
        verify_stream(folder, m)
        return dest
    with tempfile.TemporaryDirectory(dir=parent) as tmp:
        tmp = pathlib.Path(tmp)
        archive = tmp / "payload.tar.gz"
        with archive.open("wb") as out:
            verify_stream(folder, m, out)
        stage = tmp / "component"
        stage.mkdir()
        with tarfile.open(archive, "r:gz") as tar:
            members = tar.getmembers()
            require(len(members) <= 1000000 and sum(x.size for x in members) <= m["unpackedBytes"], "archive bounds")
            seen = set()
            for member in members:
                path = pathlib.PurePosixPath(member.name)
                require(path.parts and not path.is_absolute() and ".." not in path.parts and path not in seen, "unsafe archive member")
                require(member.isfile() or member.isdir() or member.issym() or member.islnk(), "special archive member")
                seen.add(path)
            tar.extractall(stage, members=members, filter="data")
        require(manifest_digest(inventory(stage)) == m["treeSha256"], "extracted tree digest mismatch")
        if dest.exists() or dest.is_symlink():
            require(not dest.is_symlink(), "unsafe installed component")
            shutil.rmtree(dest)
        stage.rename(dest)
    return dest

def main():
    p = argparse.ArgumentParser(description=__doc__)
    sub = p.add_subparsers(dest="command", required=True)
    for command in ("pack", "install"):
        a = sub.add_parser(command)
        a.add_argument("component", choices=NAMES)
        a.add_argument("folder", type=pathlib.Path)
        a.add_argument("destination", type=pathlib.Path)
        if command == "pack":
            a.add_argument("--head", required=True)
        else:
            a.add_argument("--manifest-sha", required=True)
    a = p.parse_args()
    if a.command == "pack":
        print(pack(a.component, a.folder.resolve(), a.destination.resolve(), a.head))
    else:
        require(len(a.manifest_sha) == 64 and all(c in "0123456789abcdef" for c in a.manifest_sha), "invalid manifest pin")
        print(install(a.component, a.folder.resolve(), a.destination.resolve(), a.manifest_sha))

if __name__ == "__main__":
    main()
