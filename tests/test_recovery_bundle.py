#!/usr/bin/env python3
"""Same-commit recovery bundle must reject stale, missing and forged evidence."""
import importlib.util
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "tools/analysis/recovery-bundle.py"
spec = importlib.util.spec_from_file_location("recovery_bundle", SCRIPT)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class BundleTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        base = Path(self.tmp.name)
        self.root, self.source, self.native = base / "repo", base / "source", base / "native"
        for p in (self.root, self.source, self.native):
            p.mkdir()
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        subprocess.run(["git", "-C", str(self.root), "-c", "user.name=CI",
                        "-c", "user.email=ci@example.invalid", "commit", "-qm",
                        "baseline", "--allow-empty"], check=True)
        self.sha = module.git_sha(self.root)
        for p in (self.source, self.native):
            (p / "commit.txt").write_text(self.sha)
        (self.root / "src").mkdir()
        self.jelly = []
        for name in ("a.js", "b.js"):
            relative = "src/" + name
            file = self.root / relative
            file.write_text("export function f() { return 1; }\n")
            self.jelly.append({"entry": relative, "status": "analyzed_static_candidates",
                               "runtimeCallsConfirmed": 0, "sourceSHA256": module.digest(file)})
        self.write(self.source / "function-summary.json", {
            "modules": 2551, "functions": 168103, "callExpressions": 649642,
            "parseFailed": 0, "missingRelativeImports": 0, "sourceFingerprint": "f" * 64})
        self.write(self.source / "code-graph.json", {
            "errors": [], "summary": {"modules": 2553, "functionEntities": 168103}})
        self.write(self.source / "jelly" / "111-jelly-evidence.json", {
            "requestedEntries": 2, "successes": 2, "records": self.jelly})
        (self.source / "node-runtime-tests.ok").write_text(self.sha)
        (self.root / "resources/bunfs").mkdir(parents=True)
        for name in module.ADDONS:
            file = self.root / "resources/bunfs" / name
            file.write_bytes(b"\x7fELF" + name.encode("utf8"))
            folder = self.native / name.removesuffix(".node")
            folder.mkdir()
            self.write(folder / "summary.json", {"tool": "Ghidra", "total_functions": 2,
                       "decompiled_functions": 1, "failed_functions": 1})
            (folder / "functions.tsv").write_text("address\tname\tstatus\terror\n0x1\tf\tok\t\n0x2\tg\tfailed\terror\n")
            (folder / "decompiled.c").write_text("/* Ghidra generated pseudocode */\n" + "void f() {}\n" * 15)
            (folder / "ghidra.log").write_text("analysis completed\n")
            (folder / "input.sha256").write_text(module.digest(file) + "  " + str(file))
        self.output = base / "bundle"

    def write(self, path, content):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(content))

    def test_valid_evidence_is_provenance_pinned_and_honest(self):
        report = module.build(self.root, self.source, self.native, self.output)
        self.assertEqual(report["commit"], self.sha)
        self.assertFalse(report["originalMainELFDecompiled"])
        self.assertFalse(report["originalJSCBytecodeRecovered"])
        self.assertEqual(len(report["native"]), 2)
        self.assertTrue((self.output / "SHA256SUMS").is_file())

    def test_rejects_mismatched_commit(self):
        (self.native / "commit.txt").write_text("0" * 40)
        with self.assertRaisesRegex(ValueError, "different commit"):
            module.build(self.root, self.source, self.native, self.output)

    def test_rejects_modified_native(self):
        (self.root / "resources/bunfs/audio-capture.node").write_bytes(b"forged")
        with self.assertRaisesRegex(ValueError, "binary hash mismatch"):
            module.build(self.root, self.source, self.native, self.output)

    def test_rejects_incomplete_function_inventory(self):
        report_path = self.native / "clipboard-napi" / "summary.json"
        report = json.loads(report_path.read_text())
        report["total_functions"] = 4
        self.write(report_path, report)
        with self.assertRaisesRegex(ValueError, "Ghidra counts"):
            module.build(self.root, self.source, self.native, self.output)

    def test_rejects_unverified_jelly_reference(self):
        self.jelly[0]["sourceSHA256"] = "b" * 64
        self.write(self.source / "jelly" / "111-jelly-evidence.json",
                   {"requestedEntries": 2, "successes": 2, "records": self.jelly})
        with self.assertRaisesRegex(ValueError, "Jelly input fingerprint"):
            module.build(self.root, self.source, self.native, self.output)

    def test_rejects_missing_runtime_proof(self):
        (self.source / "node-runtime-tests.ok").unlink()
        with self.assertRaises((FileNotFoundError, ValueError)):
            module.build(self.root, self.source, self.native, self.output)

if __name__ == "__main__":
    unittest.main()
