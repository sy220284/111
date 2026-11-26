import pathlib
import tempfile
import unittest
from host_components import bundle, install, selected

class HostComponentTests(unittest.TestCase):
    def test_exact_source_and_cache_reuse(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=pathlib.Path(tmp)
            src=root/"source"
            src.mkdir()
            (src/"dependency").write_text("verified")
            bundles=root/"bundles"
            bundles.mkdir()
            pins=root/"pins"
            pins.mkdir()
            head="a"*40
            pin=bundle.pack("cargo",src,bundles/"cargo",head)
            (pins/"cargo.sha256").write_text(pin)
            target=root/"install"
            with self.assertRaisesRegex(ValueError,"source mismatch"):
                install(target,bundles,pins,"b"*40,["cargo"])
            paths=install(target,bundles,pins,head,["cargo"])
            dep=pathlib.Path(paths["cargo"])/"dependency"
            self.assertEqual(dep.read_text(),"verified")
            dep.write_text("updated")
            self.assertEqual(install(target,bundles,pins,head,["cargo"]),paths)
            self.assertEqual(dep.read_text(),"updated")
            install(target,bundles,pins,head,["cargo"],repair=True)
            self.assertEqual(dep.read_text(),"verified")
            self.assertEqual(selected(target)["cargo"],{"head":head,"pin":pin})
            (pins/"cargo.sha256").write_text("c"*64)
            with self.assertRaisesRegex(ValueError,"manifest digest mismatch"):
                install(target,bundles,pins,head,["cargo"])
if __name__=="__main__":
    unittest.main()
