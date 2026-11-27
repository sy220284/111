#!/usr/bin/env python3
"""Read a reviewed npm distribution lock and verify its exact bytes."""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile

HERE = Path(__file__).resolve().parent
LOCK_PATH = HERE / "npm-lock.json"

def spec():
    record = json.loads(LOCK_PATH.read_text())
    expected = json.loads((HERE/"runtime-lock.json").read_text())["runtimeOverrides"]["npm"]
    if record["version"] != expected or not re.fullmatch(r"[a-f0-9]{128}", record.get("sha512") or ""):
        raise ValueError("npm distribution has not been locked to verified registry bytes")
    return record

def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha512").hexdigest()

def fetch(directory):
    item = spec()
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    target = directory / ("npm-"+item["version"]+".tgz")
    if target.is_file() and digest(target) == item["sha512"]:
        return target
    with tempfile.TemporaryDirectory(dir=directory) as d:
        archive = Path(d)/target.name
        subprocess.run(["curl","--fail","--silent","--show-error","--location","--retry","3","--proto","=https","--proto-redir","=https",
                        "https://registry.npmjs.org/npm/-/"+target.name,"-o",str(archive)],check=True)
        if digest(archive) != item["sha512"]:
            raise ValueError("npm registry archive SHA-512 mismatch")
        archive.replace(target)
    return target

if __name__ == "__main__":
    action = sys.argv[1]
    if action in ("version","sha512") and len(sys.argv)==2:
        print(spec()[action])
    elif action=="fetch" and len(sys.argv)==3:
        print(fetch(sys.argv[2]))
    else:
        raise SystemExit("usage: npm_archive.py version|sha512|fetch <cache>")
