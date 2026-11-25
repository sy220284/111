#!/usr/bin/env python3
"""Require the actual source archive and both exact Git executable digests."""
import hashlib
import json
import sys
from pathlib import Path
from zipfile import ZipFile

PROVENANCE = "assets/development-runtime/development-runtime.provenance.json"
NAMES = ("libcodex_git.so", "libcodex_git_remote_http.so")


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def verify(apk, source):
    with ZipFile(apk) as archive:
        provenance = json.loads(archive.read(PROVENANCE))
        if (provenance.get("abi") != "arm64-v8a" or
                provenance.get("gitHttps") is not True or
                provenance.get("gitCorrespondingSourceVerified") is not True):
            raise ValueError("ARM64 Git corresponding source is unverified")
        git = provenance["git"]
        if sha256(source) != git["sourceArchiveSha256"]:
            raise ValueError("Git corresponding source archive SHA-256 mismatch")
        for name in NAMES:
            actual = hashlib.sha256(archive.read("lib/arm64-v8a/" + name)).hexdigest()
            if actual != git["binaries"][name]:
                raise ValueError("APK Git binary SHA-256 mismatch: " + name)
    print("Git corresponding source and both ARM64 binaries verified")


if __name__ == "__main__":
    verify(Path(sys.argv[1]), Path(sys.argv[2]))
