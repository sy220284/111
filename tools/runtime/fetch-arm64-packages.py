#!/usr/bin/env python3
"""Fetch only the pinned Android ARM64 package inputs; never install a host toolchain."""
import json
import pathlib
import subprocess
import sys
import tempfile

from verified_archive import verify_zip, extract_payload, require

LOCK = json.loads(pathlib.Path(__file__).with_name("runtime-lock.json").read_text())

def fetch(output):
    output = pathlib.Path(output).resolve()
    require(not output.exists(), "package cache output already exists")
    source = LOCK["source"]
    spec = LOCK["packageArchive"]
    with tempfile.TemporaryDirectory(dir=output.parent) as tmp:
        temp = pathlib.Path(tmp)
        archive = temp / "packages.zip"
        with archive.open("wb") as stream:
            subprocess.run(["gh", "api", f"repos/{source['repository']}/actions/artifacts/{spec['id']}/zip"], stdout=stream, check=True)
        verify_zip(archive, "runtime-cache", spec, source["head"])
        stage = temp / "stage"
        stage.mkdir()
        extract_payload(archive, spec, stage, temp)
        cache = stage / spec["root"]
        for name in ("git", "node", "python"):
            require((cache / ("termux-" + name) / "index-aarch64/InRelease").is_file(),
                    "missing signed Android package index: " + name)
        cache.rename(output)

if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: fetch-arm64-packages.py <output>")
    fetch(sys.argv[1])
