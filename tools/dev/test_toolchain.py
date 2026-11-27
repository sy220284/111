#!/usr/bin/env python3
import importlib.util
import pathlib
import tempfile
import unittest
import zipfile
import hashlib

p = pathlib.Path(__file__).with_name("ai-toolchain.py")
s = importlib.util.spec_from_file_location("toolchain", p)
m = importlib.util.module_from_spec(s)
s.loader.exec_module(m)

class ToolchainTests(unittest.TestCase):
    def test_project_versions(self):
        m.project_check()

    def test_reject_wrong_digest(self):
        with tempfile.TemporaryDirectory() as d:
            path = pathlib.Path(d) / "artifact.zip"
            path.write_bytes(b"wrong")
            with self.assertRaises(ValueError):
                m.verify_zip(path, "actionlint", m.COMPONENTS["actionlint"])

    def test_locked_777_source_and_111_baseline_are_distinct(self):
        self.assertEqual(m.LOCK["source"]["run"], 44730301955)
        self.assertEqual(m.LOCK["project"]["gradle"], "9.9.2")
        self.assertNotEqual(m.LOCK["incompatible777"]["gradle"], m.LOCK["project"]["gradle"])
        self.assertEqual(m.LOCK["project"]["minSdk"], 29)

if __name__ == "__main__":
    unittest.main()
