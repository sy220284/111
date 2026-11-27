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
                # Even a sealed inode may be replaced by its directory owner;
                # the next verification must detect the replacement.
                (dest/"bin/tool").unlink()
                (dest/"bin/tool").write_bytes(b"wrong")
                self.assertEqual(m.install("jdk", root/"bundle", root/"installed", pin), dest)
                self.assertEqual((dest/"bin/tool").read_bytes(), b"tool")
                (root/"bundle/payload.000").write_bytes(b"broken")
                with self.assertRaises(ValueError):
                    m.install("jdk", root/"bundle", root/"installed", pin)
            finally:
                m.PART_SIZE = old

    def test_verity_digest_matches_independent_full_tree_reference(self):
        import hashlib
        import struct
        with tempfile.TemporaryDirectory() as d:
            file=pathlib.Path(d)/"payload"
            for size in (0, 1, 4095, 4096, 4097, 128*4096, 129*4096, 4*1024*1024+7):
                raw=(bytes(range(256))*((size+255)//256))[:size]
                file.write_bytes(raw)
                hashes=[hashlib.sha256(raw[i:i+4096].ljust(4096,b"\0")).digest() for i in range(0,size,4096)]
                while len(hashes)>1:
                    hashes=[hashlib.sha256(b"".join(hashes[i:i+128]).ljust(4096,b"\0")).digest()
                            for i in range(0,len(hashes),128)]
                root=hashes[0] if hashes else bytes(32)
                descriptor=struct.pack("<BBBBIQ",1,1,12,0,0,size)+root+bytes(208)
                self.assertEqual(m.file_digests(file),(size,hashlib.sha256(raw).hexdigest(),hashlib.sha256(descriptor).hexdigest()))

    def test_unsealed_host_retains_full_tree_check_and_rejects_replacement(self):
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as d:
            root=pathlib.Path(d)
            src=root/"source"
            src.mkdir()
            (src/"file").write_bytes(b"authentic")
            pin=m.pack("jdk",src,root/"bundle","a"*40)
            manifest=m.load(root/"bundle","jdk",pin)
            self.assertIn("verityIndex",manifest)
            with patch.object(m,"measured_verity",return_value=None):
                self.assertTrue(m.verified_tree(src,manifest))
                (src/"file").write_bytes(b"tampered!")
                self.assertFalse(m.verified_tree(src,manifest))

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
