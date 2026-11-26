import importlib.util
import pathlib
import tempfile
import unittest

p = pathlib.Path(__file__).with_name("component-bundle.py")
s = importlib.util.spec_from_file_location("bundle", p)
m = importlib.util.module_from_spec(s)
s.loader.exec_module(m)

class BundleTests(unittest.TestCase):
    def test_roundtrip_shards_and_tamper(self):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            src = root / "src"
            src.mkdir()
            (src / "bin").mkdir()
            (src / "bin/tool").write_bytes(b"tool")
            (src / "bin/tool").chmod(0o755)
            (src / "alias").symlink_to("bin/tool")
            old = m.PART_SIZE
            try:
                m.PART_SIZE = 100
                pin = m.pack("jdk", src, root / "bundle", "a"*40)
                self.assertGreater(len(list((root/"bundle").glob("payload.*"))), 1)
                dest = m.install("jdk", root/"bundle", root/"installed", pin)
                self.assertEqual(m.inventory(dest), m.inventory(src))
                (dest/"bin/tool").write_bytes(b"wrong")
                self.assertEqual(m.install("jdk", root/"bundle", root/"installed", pin), dest)
                self.assertEqual((dest/"bin/tool").read_bytes(), b"tool")
                (root/"bundle/payload.000").write_bytes(b"broken")
                with self.assertRaises(ValueError):
                    m.install("jdk", root/"bundle", root/"installed", pin)
            finally:
                m.PART_SIZE = old

    def test_external_link_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            root = pathlib.Path(d)
            src = root/"src"
            src.mkdir()
            (src/"escape").symlink_to("/etc/passwd")
            with self.assertRaises(ValueError):
                m.pack("jdk", src, root/"bundle", "a"*40)

if __name__ == "__main__":
    unittest.main()
