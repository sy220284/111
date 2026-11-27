#!/usr/bin/env python3
"""Independent, content-verified 111 development components. No online installer on consumer."""
import argparse
import errno
import hashlib
import stat
import struct
import sys
import json
import os
import pathlib
import shutil
import subprocess
import tarfile
import tempfile
import time

PART_SIZE = 350 * 1024 * 1024
ROOT = pathlib.Path(__file__).resolve().parents[2]
NAMES = ("jdk", "android-sdk", "gradle", "rust", "cargo", "wheelhouse", "emulator", "runtime-arm64", "runtime-x86", "v8-arm64", "v8-x86", "llvm", "patcher")

def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()

def require(value, message):
    if not value:
        raise ValueError(message)

# Writable dependency caches are deliberately excluded. Immutable file
# digests are anchored in the producer's published manifest pin.
VERITY_COMPONENTS = {"jdk", "android-sdk", "rust", "wheelhouse", "emulator", "llvm", "patcher"}
VERITY_BLOCK = 4096
VERITY_HASHES = VERITY_BLOCK // 32
FS_IOC_ENABLE_VERITY = 0x40806685
FS_IOC_MEASURE_VERITY = 0xC0046686
VERITY_UNSUPPORTED = {errno.ENOTTY, errno.EOPNOTSUPP, errno.ENOSYS, errno.EINVAL}

def file_digests(path):
    """SHA-256 plus the Linux fs-verity SHA-256/4KiB, no-salt descriptor digest.
    Both are computed from the same bytes, with bounded Merkle working memory."""
    whole = hashlib.sha256()
    levels = []
    length = 0
    def push(level, value):
        while len(levels) <= level:
            levels.append([])
        levels[level].append(value)
        if len(levels[level]) == VERITY_HASHES:
            parent = hashlib.sha256(b"".join(levels[level])).digest()
            levels[level].clear()
            push(level + 1, parent)
    with path.open("rb") as stream:
        while block := stream.read(1024**2):
            whole.update(block)
            length += len(block)
            for offset in range(0, len(block), VERITY_BLOCK):
                leaf = block[offset:offset+VERITY_BLOCK].ljust(VERITY_BLOCK, b"\0")
                push(0, hashlib.sha256(leaf).digest())
    if length == 0:
        root = bytes(32)
    else:
        while True:
            low = next(i for i, values in enumerate(levels) if values)
            if len(levels[low]) == 1 and not any(levels[low+1:]):
                root = levels[low][0]
                break
            parent = hashlib.sha256(b"".join(levels[low]).ljust(VERITY_BLOCK, b"\0")).digest()
            levels[low].clear()
            push(low+1, parent)
    # linux/fsverity.h: 256-byte descriptor, all reserved fields zero.
    descriptor = struct.pack("<BBBBIQ", 1, 1, 12, 0, 0, length) + root.ljust(64, b"\0") + bytes(176)
    require(len(descriptor) == 256, "fs-verity descriptor size")
    return length, whole.hexdigest(), hashlib.sha256(descriptor).hexdigest()

def inventory(root, include_verity=False):
    entries = []
    for path in sorted(root.rglob("*")):
        rel = path.relative_to(root).as_posix()
        if path.is_symlink():
            target = os.readlink(path)
            require(not pathlib.PurePosixPath(target).is_absolute() and path.resolve().is_relative_to(root.resolve()), "external link: " + rel)
            entries.append([rel, "link", target])
        elif path.is_file():
            metadata = path.stat()
            if include_verity:
                length, sha, verity = file_digests(path)
                require(length == metadata.st_size, "component changed during digest: " + rel)
                entries.append([rel, "file", length, sha, bool(metadata.st_mode & 0o111), verity])
            else:
                entries.append([rel, "file", metadata.st_size, digest(path), bool(metadata.st_mode & 0o111)])
        elif path.is_dir():
            entries.append([rel, "dir"])
        else:
            raise ValueError("special file: " + rel)
    return entries

def manifest_digest(entries):
    return hashlib.sha256(json.dumps(entries, separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()

def plain_inventory(index):
    return [entry[:5] if entry[1] == "file" else entry for entry in index]

def measured_verity(fd, seal):
    """Return the kernel-enforced digest, or None for unsupported/unsealed
    filesystems. Integrity/I/O errors are never treated as unsupported."""
    if not sys.platform.startswith("linux"):
        return None
    import fcntl
    def measure():
        value = bytearray(struct.pack("=HH", 0, 32) + bytes(32))
        fcntl.ioctl(fd, FS_IOC_MEASURE_VERITY, value, True)
        algorithm, size = struct.unpack_from("=HH", value)
        require(algorithm == 1 and size == 32, "unexpected fs-verity hash algorithm")
        return bytes(value[4:]).hex()
    try:
        return measure()
    except OSError as error:
        if error.errno in VERITY_UNSUPPORTED:
            return None
        if error.errno != errno.ENODATA:
            raise
        if not seal:
            return None
    try:
        argument = struct.pack("=IIIIQIIQ11Q", 1, 1, VERITY_BLOCK, 0, 0, 0, 0, 0, *([0]*11))
        fcntl.ioctl(fd, FS_IOC_ENABLE_VERITY, argument)
    except OSError as error:
        if error.errno in VERITY_UNSUPPORTED:
            return None
        if error.errno != errno.EEXIST:
            raise
    return measure()

def verify_verity_tree(root, manifest, seal=False):
    """True: every file kernel-sealed to the pinned index. False: mismatch.
    None: no trusted kernel capability; caller must perform the full SHA tree."""
    index = manifest.get("verityIndex")
    if index is None or not sys.platform.startswith("linux"):
        return None
    require(manifest_digest(plain_inventory(index)) == manifest["treeSha256"], "verity index tree mismatch")
    paths = sorted(root.rglob("*"))
    if len(paths) != len(index):
        return False
    for path, entry in zip(paths, index):
        if path.relative_to(root).as_posix() != entry[0]:
            return False
        if path.is_symlink():
            target = os.readlink(path)
            if entry != [entry[0], "link", target] or pathlib.PurePosixPath(target).is_absolute() or not path.resolve().is_relative_to(root.resolve()):
                return False
        elif path.is_dir():
            if entry != [entry[0], "dir"]:
                return False
        elif path.is_file() and entry[1] == "file" and len(entry) == 6:
            fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC)
            try:
                metadata = os.fstat(fd)
                if not stat.S_ISREG(metadata.st_mode) or metadata.st_size != entry[2] or bool(metadata.st_mode & 0o111) != entry[4]:
                    return False
                measured = measured_verity(fd, seal)
                if measured is None:
                    return None
                if measured != entry[5]:
                    return False
            finally:
                os.close(fd)
        else:
            return False
    return True

def verified_tree(root, manifest, seal=False):
    result = verify_verity_tree(root, manifest, seal)
    if result is not None:
        return result
    # Legacy manifests and hosts without fs-verity retain the original complete
    # cryptographic tree check. chmod or an untrusted local receipt never grants trust.
    return manifest_digest(inventory(root)) == manifest["treeSha256"]

def pack(name, folder, out, head):
    require(folder.is_dir() and not folder.is_symlink(), "component input missing")
    out.mkdir(parents=True, exist_ok=False)
    indexed = name in VERITY_COMPONENTS
    entries = inventory(folder, include_verity=indexed)
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
         "archiveSha256": archive_hash.hexdigest(), "unpackedBytes": size,
         "treeSha256": manifest_digest(plain_inventory(entries) if indexed else entries), "parts": parts}
    if indexed:
        m["verityIndex"] = entries
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
        print(f"Verified {manifest['component']} part {spec['name']}: {count} bytes", file=sys.stderr, flush=True)
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
    if dest.is_dir() and not dest.is_symlink() and verified_tree(dest, m):
        verify_stream(folder, m)
        return dest
    with tempfile.TemporaryDirectory(dir=parent) as tmp:
        tmp = pathlib.Path(tmp)
        archive = tmp / "payload.tar.gz"
        with archive.open("wb") as out:
            verify_stream(folder, m, out)
        stage = tmp / "component"
        stage.mkdir()
        with tarfile.open(archive, "r|gz") as tar:
            # Validate and extract each member in one decompression pass.
            seen = set()
            def checked_members():
                total = 0
                next_log = time.monotonic() + 15
                for member in tar:
                    total += member.size
                    require(len(seen) < 1000000 and total <= m["unpackedBytes"], "archive bounds")
                    path = pathlib.PurePosixPath(member.name)
                    require(path.parts and not path.is_absolute() and ".." not in path.parts and path not in seen, "unsafe archive member")
                    require(member.isfile() or member.isdir() or member.issym() or member.islnk(), "special archive member")
                    seen.add(path)
                    yield member
                    if time.monotonic() >= next_log:
                        print(f"Installing {name}: {len(seen)} entries, {total}/{m['unpackedBytes']} bytes", file=sys.stderr, flush=True)
                        next_log = time.monotonic() + 15
            tar.extractall(stage, members=checked_members(), filter="data")
        print(f"Verifying extracted {name} tree", file=sys.stderr, flush=True)
        require(verified_tree(stage, m, seal=True), "extracted tree digest mismatch")
        print(f"Verified extracted {name} tree", file=sys.stderr, flush=True)
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
