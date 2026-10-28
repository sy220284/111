#!/usr/bin/env python3
"""Verify and bundle same-commit AST/Jelly/real-native evidence without claiming original parity."""
import argparse
import csv
import hashlib
import json
import shutil
import subprocess
from pathlib import Path

ADDONS = ("audio-capture.node", "clipboard-napi.node")

def require(condition, message):
    if not condition:
        raise ValueError(message)

def digest(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for part in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(part)
    return h.hexdigest()

def load(path):
    path = Path(path)
    require(path.is_file() and path.stat().st_size > 0, "missing evidence: " + str(path))
    return json.loads(path.read_text(encoding="utf-8"))

def git_sha(root):
    return subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip()

def build(root, source, native, output):
    root, source, native, output = map(lambda p: Path(p).resolve(), (root, source, native, output))
    commit = git_sha(root)
    for stage, folder in (("source", source), ("native", native)):
        require(folder.is_dir(), "missing stage: " + stage)
        require((folder / "commit.txt").read_text(encoding="utf-8").strip() == commit,
                stage + " evidence was built at a different commit")

    functions = load(source / "function-summary.json")
    graph = load(source / "code-graph.json")
    jelly = load(source / "jelly" / "111-jelly-evidence.json")
    require(functions["modules"] == 2551 and functions["parseFailed"] == 0 and
            functions["missingRelativeImports"] == 0, "incomplete source AST inventory")
    require(graph["errors"] == [] and graph["summary"]["modules"] == 2553,
            "source graph has errors or incomplete modules")
    require(graph["summary"]["functionEntities"] >= functions["functions"],
            "source graph function count does not cover indexed functions")
    require(jelly["requestedEntries"] == 2 and jelly["successes"] == 2,
            "bounded Jelly probe incomplete")
    for record in jelly["records"]:
        relative = Path(record["entry"])
        require(not relative.is_absolute() and ".." not in relative.parts and
                relative.parts[0] == "src", "unsafe Jelly source path")
        require(record["status"] == "analyzed_static_candidates" and
                record["runtimeCallsConfirmed"] == 0 and
                record["sourceSHA256"] == digest(root / relative),
                "Jelly input fingerprint or status mismatch: " + str(relative))
    require((source / "node-runtime-tests.ok").read_text(encoding="utf-8").strip() == commit,
            "Node CLI/MCP runtime tests not completed for source commit")
    original = []
    for name in ADDONS:
        location = "resources/bunfs/" + name
        folder = native / name.removesuffix(".node")
        report = load(folder / "summary.json")
        expected = digest(root / location)
        saved = (folder / "input.sha256").read_text(encoding="utf-8").split()[0]
        require(saved == expected, "native binary hash mismatch: " + name)
        require(report["tool"] == "Ghidra" and report["total_functions"] > 0 and
                report["decompiled_functions"] > 0 and
                report["total_functions"] == report["decompiled_functions"] + report["failed_functions"],
                "invalid Ghidra counts: " + name)
        rows = list(csv.DictReader((folder / "functions.tsv").open(encoding="utf-8"), delimiter="\t"))
        require(len(rows) == report["total_functions"] and
                sum(r["status"] == "ok" for r in rows) == report["decompiled_functions"] and
                sum(r["status"] == "failed" for r in rows) == report["failed_functions"],
                "Ghidra function-level evidence inconsistent: " + name)
        require((folder / "decompiled.c").stat().st_size > 100 and
                (folder / "ghidra.log").is_file(), "missing native pseudocode/log: " + name)
        original.append({"path": location, "sha256": expected,
                         "functions": report["total_functions"],
                         "decompiled": report["decompiled_functions"],
                         "failed": report["failed_functions"]})
    require(not output.exists() or not any(output.iterdir()), "output must be empty")
    output.mkdir(parents=True, exist_ok=True)
    shutil.copytree(source, output / "source", dirs_exist_ok=True)
    shutil.copytree(native, output / "native", dirs_exist_ok=True)
    summary = {
        "schema": 1, "kind": "111-same-commit-recovery-evidence",
        "commit": commit, "sourceFingerprint": functions["sourceFingerprint"],
        "sourceModules": functions["modules"], "astFunctions": functions["functions"],
        "callExpressions": functions["callExpressions"],
        "graphModules": graph["summary"]["modules"],
        "jellyScope": "two real modules, bounded static candidates only",
        "jellyExecutedCallsConfirmed": 0,
        "nodeRuntimeScope": "local model SSE and MCP test fixtures",
        "native": original,
        "originalMainELFIncluded": False, "originalMainELFDecompiled": False,
        "originalJSCBytecodeRecovered": False, "crossLanguageFunctionMappingVerified": False,
        "originalProgramBehaviorEquivalent": False,
        "evidenceStatus": "verified_same_commit_partial_recovery_evidence"
    }
    (output / "SUMMARY.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
                                          encoding="utf-8")
    files = sorted(p for p in output.rglob("*") if p.is_file() and p.name != "SHA256SUMS")
    (output / "SHA256SUMS").write_text(
        "".join(digest(p) + "  " + p.relative_to(output).as_posix() + "\n" for p in files),
        encoding="utf-8")
    return summary

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", required=True)
    parser.add_argument("--source", required=True)
    parser.add_argument("--native", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    print(json.dumps(build(args.root, args.source, args.native, args.output), ensure_ascii=False))

if __name__ == "__main__":
    main()
