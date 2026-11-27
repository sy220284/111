import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from v8_toolchain_provenance import provenance

class OfficialV8ToolsTests(unittest.TestCase):
    def fixture(self, root):
        (root/"bin").mkdir()
        (root/"lib").mkdir()
        for name,version in (("clang","clang version 23.1.0"),("ninja","1.12.1"),("pkg-config","1.8.1")):
            path=root/"bin"/name
            path.write_text("#!/bin/sh\nprintf '%s\\n' '"+version+"'\n")
            path.chmod(0o755)
        (root/"lib/libclang.so").write_bytes(b"official libclang")

    def test_official_tools_record_exact_bytes_versions_and_packages(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            self.fixture(root)
            from subprocess import check_output
            def output(args,**kwargs):
                return "clang-23\t21.1.0\n" if args[0]=="dpkg-query" else check_output(args,**kwargs)
            with patch("v8_toolchain_provenance.shutil.which",side_effect=lambda n:str(root/"bin"/n)),patch("v8_toolchain_provenance.subprocess.check_output",side_effect=output):
                records=provenance(root)
                self.assertEqual("clang version 23.1.0",records["clang"]["version"])
                self.assertEqual("1.12.1",records["ninja"]["version"])
                self.assertEqual(hashlib.sha256(b"official libclang").hexdigest(),records["libclang"]["sha256"])
                self.assertEqual("clang-23\t21.1.0\n",records["packages"])
                clang=root/"bin/clang"
                clang.write_text(clang.read_text().replace("21.1.0","18.1.0"))
                with self.assertRaises(ValueError):
                    provenance(root)
