#!/usr/bin/env python3
"""Content-addressed index of native artifacts; the binary stays in Actions storage."""
import argparse
import hashlib
import json
from pathlib import Path
from select_native_artifacts import ArtifactSelector

def fingerprint(signature):
    return hashlib.sha256(json.dumps(signature, ensure_ascii=False, separators=(",", ":")).encode()).hexdigest()

def main():
    p = argparse.ArgumentParser()
    p.add_argument("command", choices=["create", "provider"])
    p.add_argument("--abi")
    p.add_argument("--artifact-id", type=int)
    p.add_argument("--out", required=True, type=Path)
    p.add_argument("--github-output", required=True, type=Path)
    a = p.parse_args()
    selector = ArtifactSelector()
    sha = selector.run(["git", "rev-parse", "HEAD"])
    if a.command == "create" and (not a.abi or not a.artifact_id):
        p.error("native index requires ABI and artifact ID")
    key = fingerprint(selector.test_signature(sha) if a.command == "provider" else selector.signature(sha, a.abi))
    a.out.write_text(json.dumps({"schema": 1, "source_sha": sha, "abi": a.abi, "fingerprint": key, "artifact_id": a.artifact_id}, indent=2), encoding="utf-8")
    with a.github_output.open("a", encoding="utf-8") as out:
        print(f"fingerprint={key}", file=out)

if __name__ == "__main__":
    main()
