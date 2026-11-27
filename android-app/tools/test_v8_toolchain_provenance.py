import hashlib
from pathlib import Path
import tempfile
import unittest

from v8_toolchain_provenance import provenance


class PortableV8ToolsTests(unittest.TestCase):
    def fixture(self, root):
        (root / "bin").mkdir()
        (root / "lib").mkdir()
        for name, version in (("clang", "clang version 21.1.0"), ("ninja", "1.12.1"), ("pkg-config", "1.8.1")):
            path = root / "bin" / name
            path.write_text("#!/bin/sh\nprintf '%s\\n' '" + version + "'\n")
            path.chmod(0o755)
        (root / "lib/libclang.so").write_bytes(b"verified libclang")
        (root / "packages.tsv").write_text("clang-21\t21.1.0\n")

    def test_portable_tools_supply_versions_and_exact_bytes_without_dpkg(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.fixture(root)
            records = provenance(root)
            self.assertEqual("clang version 21.1.0", records["bin/clang"]["version"])
            self.assertEqual("1.12.1", records["bin/ninja"]["version"])
            self.assertEqual(hashlib.sha256(b"verified libclang").hexdigest(), records["lib/libclang.so"]["sha256"])
            self.assertEqual((root / "packages.tsv").read_text(), records["packages.tsv"]["packages"])

    def test_wrong_version_missing_manifest_and_escaping_library_fail_before_build(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.fixture(root)
            clang = root / "bin/clang"
            original = clang.read_text()
            clang.write_text(original.replace("21.1.0", "18.1.0"))
            with self.assertRaises(ValueError):
                provenance(root)
            clang.write_text(original)
            (root / "packages.tsv").unlink()
            with self.assertRaises(FileNotFoundError):
                provenance(root)
            (root / "packages.tsv").write_text("packages")
            (root / "lib/libclang.so").unlink()
            (root / "lib/libclang.so").symlink_to("/etc/os-release")
            with self.assertRaises(ValueError):
                provenance(root)
