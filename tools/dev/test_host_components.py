import pathlib
import tempfile
import unittest
from unittest.mock import patch
from host_components import complete_index, bundle, install, selected, reviewed_artifacts, reusable, object_path, cache_downloaded_object

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
    def test_content_addressed_archive_remains_separate_from_working_copy(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=pathlib.Path(tmp)
            source=root/"source"
            source.mkdir()
            (source/"dependency").write_bytes(b"verified"*10000)
            downloaded=root/"downloaded"
            head="a"*40
            pin=bundle.pack("cargo",source,downloaded,head)
            cached=cache_downloaded_object(root,"cargo",pin,head,downloaded)
            self.assertEqual(cached,object_path(root,"cargo",pin))
            self.assertFalse(downloaded.exists())
            pins=root/"pins"
            pins.mkdir()
            (pins/"cargo.sha256").write_text(pin)
            paths=install(root,root,pins,head,["cargo"],sources={"cargo":cached})
            dep=pathlib.Path(paths["cargo"])/"dependency"
            dep.write_text("working state")
            self.assertEqual(bundle.load(cached,"cargo",pin)["head"],head)
            install(root,root,pins,head,["cargo"],repair=True,sources={"cargo":cached})
            self.assertEqual(dep.read_bytes(),b"verified"*10000)
            part=next(cached.glob("payload.*"))
            raw=bytearray(part.read_bytes())
            raw[len(raw)//2]^=1
            part.write_bytes(raw)
            with self.assertRaisesRegex(ValueError,"corrupt part"):
                install(root,root,pins,head,["cargo"],repair=True,sources={"cargo":cached})

    def test_stream_verification_checks_whole_archive_and_each_part(self):
        import hashlib
        import io
        import json
        with tempfile.TemporaryDirectory() as tmp:
            folder=pathlib.Path(tmp)
            source=folder/"source"
            source.mkdir()
            (source/"data").write_bytes(bytes(range(256))*4096)
            packed=folder/"packed"
            with patch.object(bundle,"PART_SIZE",1024):
                pin=bundle.pack("jdk",source,packed,"a"*40)
                manifest=bundle.load_manifest(packed,"jdk",pin)
                self.assertGreater(len(manifest["parts"]),1)
                sink=io.BytesIO()
                bundle.verify_stream(packed,manifest,sink)
                self.assertEqual(hashlib.sha256(sink.getvalue()).hexdigest(),manifest["archiveSha256"])
                manifest["archiveSha256"]="0"*64
                with self.assertRaisesRegex(ValueError,"archive digest mismatch"):
                    bundle.verify_stream(packed,manifest)

    def test_complete_index_reads_every_page_and_retries_concurrent_upload(self):
        first=[{"id":i} for i in range(1,101)]
        second=[{"id":101},{"id":102}]
        with patch("host_components.time.sleep") as sleep, patch("host_components.api",side_effect=[
            {"total_count":101,"artifacts":first},
            {"total_count":102,"artifacts":second},
            {"total_count":102,"artifacts":first},
            {"total_count":102,"artifacts":second},
        ]) as request:
            self.assertEqual(len(complete_index("actions/runs/7/artifacts","artifacts")),102)
            self.assertEqual(request.call_count,4)
            sleep.assert_called_once_with(1)
        with patch("host_components.time.sleep"), patch("host_components.api",return_value={
            "total_count":2,"artifacts":[{"id":1},{"id":1}]
        }):
            with self.assertRaisesRegex(ValueError,"unstable"):
                complete_index("actions/runs/7/artifacts","artifacts")

    def test_remote_producer_requires_actual_success_and_matching_attempt(self):
        head="a"*40
        run={"id":7,"head_repository":{"full_name":"sy220284/111"},"path":".github/workflows/dev-toolchain.yml","head_sha":head,"status":"completed","run_attempt":2}
        job={"id":123,"name":"111 专用 JDK、Android SDK、Gradle 离线依赖","head_sha":head,"run_attempt":2,"status":"completed","conclusion":"success","started_at":"2026-11-26T00:00:00Z","completed_at":"2026-11-26T00:05:00Z"}
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
