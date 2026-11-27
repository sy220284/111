#!/usr/bin/env python3
"""One exact ABI-aware version policy for the signed Termux runtime producer and APK assembler."""
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
LOCK = json.loads((ROOT / "tools/runtime/runtime-lock.json").read_text())
TOOLS = ("git", "curl", "jq", "ripgrep", "python", "pip", "node", "npm")
PACKAGES = (
    ("git", "git", "git"), ("git", "curl", "curl"), ("git", "jq", "jq"),
    ("git", "ripgrep", "ripgrep"), ("node", "nodejs", "node"),
    ("python", "python", "python"), ("python", "python-pip", "pip"),
)

def versions_for(abi):
    if abi not in ("arm64-v8a", "x86_64"):
        raise ValueError("unsupported ABI: " + abi)
    versions = {k: LOCK["runtime777"][k] for k in TOOLS}
    versions.update(LOCK["runtimeOverrides"])
    if versions["ripgrep"] != LOCK["runtimeX86"]["ripgrep"]:
        raise ValueError("ripgrep must use one version across Android ABIs")
    return versions

def package_records(prepared, group, abi):
    manifest = prepared / group / "assets/runtime" / group / abi / "packages.tsv"
    rows = [line.split("\t") for line in manifest.read_text().splitlines()]
    if not rows or any(len(row) != 4 or not re.fullmatch(r"[0-9a-f]{64}", row[3]) for row in rows):
        raise ValueError("invalid signed package manifest: " + group)
    result = {row[0]: {"version": row[1], "filename": row[2], "sha256": row[3]} for row in rows}
    if len(result) != len(rows):
        raise ValueError("duplicate signed package manifest")
    return result

def verify_versions(prepared, abi):
    expected = versions_for(abi)
    records = {group: package_records(prepared, group, abi) for group in ("git", "node", "python")}
    for group, package, key in PACKAGES:
        actual = records[group].get(package, {}).get("version")
        if actual != expected[key]:
            raise ValueError(f"{abi} locked package drift: {package}: expected {expected[key]}, actual {actual}")
    if abi == "arm64-v8a" and records["git"]["git"]["sha256"] != LOCK["runtime777"]["gitPackageSha256"]:
        raise ValueError("ARM64 Git package digest drift")
    npm = prepared / "node/assets/runtime/node/npm/package/package.json"
    actual = json.loads(npm.read_text())["version"]
    if actual != expected["npm"]:
        raise ValueError(f"{abi} npm version drift: expected {expected['npm']}, actual {actual}")
    return records

if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("usage: runtime_versions.py <prepared-dir> <abi>")
    prepared, abi = pathlib.Path(sys.argv[1]), sys.argv[2]
    records = verify_versions(prepared, abi)
    print(json.dumps({"abi": abi, "versions": versions_for(abi), "packages": records}, indent=2))
