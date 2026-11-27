"""Verify and unpack a pinned single-part GitHub package archive, without installing host tools."""
import hashlib
import pathlib
import shutil
import tarfile
import zipfile

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

def verify_zip(path, component, spec, source_head):
    require(path.is_file() and path.stat().st_size == spec["size"], "产物大小错误: " + str(path))
    require(sha256(path) == spec["sha256"], "产物 SHA-256 不匹配: " + str(path))
    with zipfile.ZipFile(path) as z:
        require(sorted(z.namelist()) == ["SHA256SUMS", "component.env", "payload.tar.gz"], "产物布局错误")
        lines = z.read("component.env").decode().splitlines()
        meta = dict(line.split("=", 1) for line in lines)
        require(len(meta) == len(lines), "重复产物字段")
        expected = {"ARTIFACT_SCHEMA": "4", "ARTIFACT_COMPONENT": component,
                    "ARTIFACT_ARCH": "x64", "ARTIFACT_HEAD_SHA": source_head,
                    "ARTIFACT_PART": "1", "ARTIFACT_PARTS": "1"}
        require(all(meta.get(k) == v for k, v in expected.items()), "产物来源/架构/组件不匹配")
        sums = dict((name, digest) for digest, name in
                    (line.split("  ", 1) for line in z.read("SHA256SUMS").decode().splitlines()))
        require(set(sums) == {"component.env", "payload.tar.gz"}, "内部摘要清单不完整")
        for name, digest in sums.items():
            with z.open(name) as stream:
                require(digest_stream(stream) == digest, "内部摘要错误: " + name)

def extract_payload(archive, spec, stage, temp):
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
