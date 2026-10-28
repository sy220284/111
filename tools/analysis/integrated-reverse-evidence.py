#!/usr/bin/env python3
"""Build a provenance-aware report without promoting static/native evidence to parity."""
import argparse
import hashlib
import json
from pathlib import Path


def digest(file):
    h = hashlib.sha256()
    with file.open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def native_record(root, name):
    path = root / name
    summary = json.loads((path / "summary.json").read_text(encoding="utf8"))
    expected = path / "input.sha256"
    sha_line = expected.read_text(encoding="utf8").strip().split()
    if len(sha_line) < 2 or len(sha_line[0]) != 64:
        raise ValueError(f"invalid Ghidra input digest: {name}")
    total = summary["total_functions"]
    success = summary["decompiled_functions"]
    failure = summary["failed_functions"]
    if summary.get("tool") != "Ghidra" or min(total, success, failure) < 0 or total != success + failure:
        raise ValueError(f"invalid function accounting: {name}")
    rows = (path / "functions.tsv").read_text(encoding="utf8").splitlines()
    if not rows or rows[0] != "address\tname\tstatus\terror" or len(rows) != total + 1:
        raise ValueError(f"missing or inconsistent function records: {name}")
    if not (path / "decompiled.c").is_file() or not (path / "ghidra.log").is_file():
        raise ValueError(f"missing native output: {name}")
    statuses = [line.split("\t")[2] for line in rows[1:]]
    if statuses.count("ok") != success or statuses.count("failed") != failure:
        raise ValueError(f"incorrect native success/failure rows: {name}")
    return {
        "name": name, "binarySha256": sha_line[0],
        "functions": {"total": total, "decompiled": success, "failed": failure},
        "decompiledSha256": digest(path / "decompiled.c"),
        "evidence": str(path),
        "level": "machine_code_pseudocode_only",
        "originalSourceEquivalenceConfirmed": False,
    }


def build(args):
    root = Path(args.repo).resolve()
    graph = json.loads(Path(args.graph).read_text(encoding="utf8"))
    source = json.loads((root / "resources/function-summary.json").read_text(encoding="utf8"))
    if graph.get("errors") or graph.get("summary", {}).get("modules") != source["modules"] + 2:
        raise ValueError("AST graph incomplete or inconsistent with source index")
    if graph["summary"]["functionEntities"] < source["functions"]:
        raise ValueError("AST graph missing source functions")
    addons = ("audio-capture.node", "clipboard-napi.node")
    native = [native_record(Path(args.native_root), name) for name in addons]
    for item in native:
        binary = root / "resources/bunfs" / item["name"]
        if not binary.is_file() or digest(binary) != item["binarySha256"]:
            raise ValueError("native evidence binary SHA256 differs from checked-out repo: " + item["name"])
    if not args.commit or len(args.commit) != 40 or not all(c in "0123456789abcdef" for c in args.commit):
        raise ValueError("full git SHA is required")
    result = {
        "schema": 1, "kind": "111-integrated-reverse-evidence",
        "sourceCommit": args.commit,
        "sourceFingerprint": source["sourceFingerprint"],
        "source": {"modules": source["modules"], "functions": source["functions"],
                   "callExpressions": source["callExpressions"],
                   "graphSha256": digest(Path(args.graph)),
                   "graphModules": graph["summary"]["modules"],
                   "candidateCallsAreRuntimeVerified": False},
        "native": native, "ghidraToolchainProvenanceRun": str(args.toolchain_run),
        "status": {"nativeAddonPseudocode": "measured",
                   "jsStaticGraph": "measured",
                   "crossLanguageFunctionEdges": "not_implemented",
                   "jscBytecodeToSource": "not_implemented",
                   "originalMainELFDecompilation": "not_run_original_binary_unavailable",
                   "originalRuntimeParity": "not_verified"},
    }
    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf8")
    print(json.dumps({"sourceCommit": args.commit, "native": [x["functions"] for x in native],
                      "status": result["status"]}, ensure_ascii=False))
    return result


def main():
    p = argparse.ArgumentParser()
    for k in ("repo", "graph", "native-root", "commit", "toolchain-run", "output"):
        p.add_argument("--" + k, required=True)
    build(p.parse_args())


if __name__ == "__main__":
    main()
