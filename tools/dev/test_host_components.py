import pathlib
import tempfile
import unittest
from unittest.mock import patch
from host_components import bundle, install, selected, reviewed_artifacts, reusable

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
            self.assertTrue(reusable(target,"cargo",head,pin))
            (pins/"cargo.sha256").write_text("c"*64)
            with self.assertRaisesRegex(ValueError,"manifest digest mismatch"):
                install(target,bundles,pins,head,["cargo"])
    def test_remote_producer_requires_actual_success_and_matching_attempt(self):
        head="a"*40
        run={"id":7,"head_repository":{"full_name":"sy220284/111"},"path":".github/workflows/dev-toolchain.yml","head_sha":head,"status":"completed","run_attempt":2}
        job={"name":"111 专用 JDK、Android SDK、Gradle 离线依赖","head_sha":head,"run_attempt":2,"status":"completed","conclusion":"success","started_at":"2026-11-26T00:00:00Z","completed_at":"2026-11-26T00:05:00Z"}
        rows=[{"name":n,"id":i+1,"size_in_bytes":100,"digest":"sha256:"+"b"*64,"expired":False,"created_at":"2026-11-26T00:03:00Z"} for i,n in enumerate(("111-toolchain-jdk","111-toolchain-android-manifest-pins"))]
        responses=[run,{"total_count":1,"jobs":[job]},{"total_count":2,"artifacts":rows}]
        with patch("host_components.api",side_effect=responses):
            self.assertEqual(reviewed_artifacts(7,["jdk"])[0],head)
        rows[0]["created_at"]="2026-11-25T23:59:00Z"
        with patch("host_components.api",side_effect=responses):
            with self.assertRaisesRegex(ValueError,"another attempt"):
                reviewed_artifacts(7,["jdk"])
        job["conclusion"]="failure"
        with patch("host_components.api",side_effect=responses):
            with self.assertRaisesRegex(ValueError,"not accepted"):
                reviewed_artifacts(7,["jdk"])
if __name__=="__main__":
    unittest.main()
