import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

FILE = Path(__file__).resolve().parents[1] / "tools/analysis/integrated-reverse-evidence.py"
spec = importlib.util.spec_from_file_location("integrated", FILE)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


class TestIntegratedEvidence(unittest.TestCase):
    def test_native_record_rejects_false_function_accounting(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp) / "audio-capture.node"
            folder.mkdir()
            (folder / "summary.json").write_text(json.dumps({
                "tool": "Ghidra", "total_functions": 2,
                "decompiled_functions": 1, "failed_functions": 0}))
            (folder / "input.sha256").write_text("a" * 64 + "  /tmp/input\n")
            (folder / "functions.tsv").write_text("address\tname\tstatus\terror\n")
            (folder / "decompiled.c").write_text("C")
            (folder / "ghidra.log").write_text("log")
            with self.assertRaisesRegex(ValueError, "accounting"):
                mod.native_record(Path(tmp), "audio-capture.node")

    def test_native_record_requires_real_function_rows(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp) / "clipboard-napi.node"
            folder.mkdir()
            (folder / "summary.json").write_text(json.dumps({
                "tool": "Ghidra", "total_functions": 1,
                "decompiled_functions": 1, "failed_functions": 0}))
            (folder / "input.sha256").write_text("b" * 64 + "  /tmp/input\n")
            (folder / "functions.tsv").write_text("address\tname\tstatus\terror\n")
            (folder / "decompiled.c").write_text("C")
            (folder / "ghidra.log").write_text("log")
            with self.assertRaisesRegex(ValueError, "function records"):
                mod.native_record(Path(tmp), "clipboard-napi.node")

    def test_native_record_keeps_pseudocode_caveat(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp) / "audio-capture.node"
            folder.mkdir()
            (folder / "summary.json").write_text(json.dumps({
                "tool": "Ghidra", "total_functions": 1,
                "decompiled_functions": 1, "failed_functions": 0}))
            (folder / "input.sha256").write_text("c" * 64 + "  /tmp/input\n")
            (folder / "functions.tsv").write_text("address\tname\tstatus\terror\n1\ttest\tok\t\n")
            (folder / "decompiled.c").write_text("int test(void) { return 1; }")
            (folder / "ghidra.log").write_text("log")
            result = mod.native_record(Path(tmp), "audio-capture.node")
            self.assertEqual(result["functions"]["decompiled"], 1)
            self.assertFalse(result["originalSourceEquivalenceConfirmed"])
            self.assertEqual(result["level"], "machine_code_pseudocode_only")


if __name__ == "__main__":
    unittest.main()
