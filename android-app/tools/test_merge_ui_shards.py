import json
import tempfile
import unittest
import struct
import zlib
from pathlib import Path
from unittest.mock import patch

from device_matrix import CONFIG, SHARDS, dimensions, shard_classes
from run_ui_device_checks import PACKAGE
from merge_ui_shards import merge


def chunk(kind, payload):
    return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload))


def png():
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 1,1,8,2,0,0,0)) + chunk(b"IDAT", zlib.compress(b"\x00\xff\x00\x00")) + chunk(b"IEND", b"")


class ShardMergeTests(unittest.TestCase):
    def fixture(self, root):
        root.mkdir()
        for shard in SHARDS:
            directory = root / f"ui-shard-mainstream-adaptive-{shard}"
            screens = directory / "ui-screenshots-adaptive"
            screens.mkdir(parents=True)
            (screens / f"{shard}-android.png").write_bytes(png())
            ids = [PACKAGE + "." + c + f"#test{i}" for c in shard_classes("mainstream","adaptive",shard) for i in range(40)]
            common = dimensions("mainstream","adaptive") | dict(profile="mainstream",mode="adaptive",audit_theme="dark",shard=shard,commit="head",android_api=CONFIG["api"],page_size=CONFIG["page_size"],build_fingerprint="fingerprint",android_release="17",physical_size="1272x2772",physical_density_dpi=480,width_dp=424,height_dp=800,run_id=shard)
            report = common | dict(status="passed",tests=len(ids),test_ids=ids,missing_screens=[],screenshot_collection_exit=0)
            manifest = common | dict(present=[shard],required=[shard],discovered=[shard],missing=[],screenshot_dimensions={shard:{"width_px":1,"height_px":1}})
            (directory/"ui-shard-input.json").write_text(json.dumps(dict(source="head",profile="mainstream",mode="adaptive",shard=shard,apk={"debug/app-debug.apk":"a"*64,"androidTest/debug/app-debug-androidTest.apk":"b"*64})))
            (directory/"ui-adaptive-diagnosis.json").write_text(json.dumps(report))
            (screens/"manifest.json").write_text(json.dumps(manifest))

    def run_case(self, change=None):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)/"shards"; output=Path(d)/"output"; output.mkdir()
            self.fixture(root)
            if change: change(root)
            with patch("merge_ui_shards.REQUIRED_SCREENS",list(SHARDS)):
                return merge(root,output,"mainstream","adaptive","head")

    def test_full_union(self):
        result=self.run_case()
        self.assertEqual(240,result["tests"])
        self.assertEqual(set(SHARDS),set(result["shard_run_ids"]))

    def test_wrong_theme_excludes_screenshot_evidence(self):
        def change(root):
            path=root/"ui-shard-mainstream-adaptive-navigation/ui-screenshots-adaptive/manifest.json"
            data=json.loads(path.read_text()); data["audit_theme"]="light"; path.write_text(json.dumps(data))
        result=self.run_case(change)
        self.assertEqual("passed",result["status"])
        self.assertEqual("incomplete",result["screenshot_observation"]["status"])
        self.assertIn("navigation",result["missing_screens"])

    def test_missing_shard_is_rejected(self):
        with self.assertRaises(ValueError):
            self.run_case(lambda root: (root/"ui-shard-mainstream-adaptive-navigation").rename(root/"wrong"))

    def test_failed_or_stale_shard_is_rejected(self):
        def change(root):
            path=root/"ui-shard-mainstream-adaptive-navigation/ui-adaptive-diagnosis.json"
            report=json.loads(path.read_text()); report["commit"]="old"; path.write_text(json.dumps(report))
        with self.assertRaises(ValueError):
            self.run_case(change)

    def test_missing_screenshot_is_recorded(self):
        result = self.run_case(lambda root: (root/"ui-shard-mainstream-adaptive-navigation/ui-screenshots-adaptive/navigation-android.png").unlink())
        self.assertEqual("passed", result["status"])
        self.assertEqual("incomplete", result["screenshot_observation"]["status"])
        self.assertIn("navigation", result["missing_screens"])

    def test_duplicate_run_is_rejected(self):
        def change(root):
            folder=root/"ui-shard-mainstream-adaptive-components"
            for path in (folder/"ui-adaptive-diagnosis.json",folder/"ui-screenshots-adaptive/manifest.json"):
                data=json.loads(path.read_text()); data["run_id"]="navigation"; path.write_text(json.dumps(data))
        with self.assertRaises(ValueError):
            self.run_case(change)

    def test_duplicate_screenshot_is_recorded(self):
        def change(root):
            folder=root/"ui-shard-mainstream-adaptive-components/ui-screenshots-adaptive"
            (folder/"components-android.png").rename(folder/"navigation-android.png")
            path=folder/"manifest.json"; data=json.loads(path.read_text())
            data.update(present=["navigation"],required=["navigation"],discovered=["navigation"],screenshot_dimensions={"navigation":{"width_px":1,"height_px":1}})
            path.write_text(json.dumps(data))
        result = self.run_case(change)
        self.assertEqual("passed", result["status"])
        self.assertTrue(any("duplicate screenshot name" in error for error in result["screenshot_observation"]["errors"]))

    def test_different_apk_bytes_are_rejected(self):
        def change(root):
            path=root/"ui-shard-mainstream-adaptive-navigation/ui-shard-input.json"
            record=json.loads(path.read_text())
            record["apk"]["debug/app-debug.apk"]="c"*64
            path.write_text(json.dumps(record))
        with self.assertRaises(ValueError): self.run_case(change)

    def test_reused_shard_without_trusted_proof_is_rejected(self):
        def change(root):
            (root/"ui-shard-mainstream-adaptive-navigation/ui-shard-origin.json").write_text("{}")
        with self.assertRaises(ValueError): self.run_case(change)
