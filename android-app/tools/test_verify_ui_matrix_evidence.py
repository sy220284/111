import json
import tempfile
import unittest
from pathlib import Path
from device_matrix import CONFIG, MODES, PROFILES, dimensions, INDEPENDENT_IDS, independent_device, SHARDS
from verify_ui_matrix_evidence import validate

class MatrixEvidenceTests(unittest.TestCase):
    def build(self, root):
        for profile in PROFILES:
            for mode in MODES:
                folder = root/f"ui-proof-{profile}-{mode}"
                folder.mkdir()
                (folder/"ui-visual-evidence").mkdir()
                ids = [f"Example#test{i}" for i in range(CONFIG["minimum_ui_tests"]-len(INDEPENDENT_IDS))]
                if independent_device(profile,mode): ids+=sorted(INDEPENDENT_IDS)
                self.write(folder/f"ui-{mode}-diagnosis.json", dict(dimensions(profile,mode),
                    status="passed", commit="head", profile=profile, mode=mode,
                    android_api=CONFIG["api"], page_size=CONFIG["page_size"], build_fingerprint="android",
                    missing_screens=[], screenshot_collection_exit=0, run_id=profile+mode, shard_run_ids={s:profile+mode+s for s in SHARDS},
                    tested_apk_sha256={"debug/app-debug.apk":"a"*64,"androidTest/debug/app-debug-androidTest.apk":"b"*64},
                    shard_source_commits={s:"head" for s in SHARDS},shard_origins={}, tests=len(ids), test_ids=ids))
                self.write(folder/"ui-visual-evidence/report.json", {"status":"baseline_pending","structural_errors":[]})
                if mode=="baseline":
                    self.write(folder/"ui-motion-diagnosis.json",{"status":"passed","tests":1})
                    self.write(folder/"ui-motion-performance.json",{"frames":12,
                        "frame_ns":[20_000_000]*12,"deadline_ns":[16_666_667]*12,
                        "listener_dropped":0,"renderer_pipelines":["Pipeline=Skia (OpenGL)"],
                        "p95_ms":20.0,"very_slow_frames":0})
                    if PROFILES[profile]["self_update"]:
                        self.write(folder/"self-update-open.json",{"status":"passed"})
    def write(self,path,obj):
        path.write_text(json.dumps(obj))
    def test_complete_four_mode_matrix(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d); self.build(root)
            self.assertEqual((CONFIG["minimum_ui_tests"]-len(INDEPENDENT_IDS))*4+len(INDEPENDENT_IDS),validate(root,"head")["executions"])
    def test_wrong_test_set_same_count_is_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d); self.build(root)
            path=root/"ui-proof-mainstream-adaptive/ui-adaptive-diagnosis.json"
            data=json.loads(path.read_text()); data["test_ids"][-1]="Different#test"; self.write(path,data)
            with self.assertRaises(ValueError): validate(root,"head")
    def test_missing_and_stale_proof_are_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d); self.build(root)
            with self.assertRaises(ValueError): validate(root,"different")
            (root/"ui-proof-mainstream-adaptive").rename(root/"unexpected")
            with self.assertRaises(ValueError): validate(root,"head")
    def test_visual_and_motion_failures_block(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d); self.build(root)
            self.write(root/"ui-proof-mainstream-baseline/ui-motion-performance.json",{"frames":0})
            with self.assertRaises(ValueError): validate(root,"head")

    def test_reports_multiple_failed_devices_without_accepting_them(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d); self.build(root)
            self.write(root/"ui-proof-mainstream-baseline/ui-motion-diagnosis.json", {"status":"failed"})
            (root/"ui-proof-ultra-adaptive/ui-adaptive-diagnosis.json").unlink()
            with self.assertRaises(ValueError) as caught:
                validate(root,"head")
            self.assertIn("mainstream/baseline", str(caught.exception))
            self.assertIn("ultra/adaptive", str(caught.exception))

    def test_installer_contract_must_run_once_on_designated_device(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d); self.build(root)
            path=root/"ui-proof-mainstream-baseline/ui-baseline-diagnosis.json"
            data=json.loads(path.read_text()); data["test_ids"][-1]="Example#fake"; self.write(path,data)
            with self.assertRaises(ValueError): validate(root,"head")

    def test_shard_ids_cannot_be_reused_across_configurations(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d); self.build(root)
            path=root/"ui-proof-ultra-baseline/ui-baseline-diagnosis.json"
            data=json.loads(path.read_text()); data["shard_run_ids"]["navigation"]="mainstreamadaptivenavigation"; self.write(path,data)
            with self.assertRaises(ValueError): validate(root,"head")

    def test_removed_compact_profile_cannot_supply_evidence(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d); self.build(root)
            (root/"ui-proof-compact-baseline").mkdir()
            with self.assertRaisesRegex(ValueError, "unexpected"):
                validate(root,"head")

    def test_raw_motion_evidence_rejects_wrong_renderer_drops_and_false_summary(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d); self.build(root)
            path=root/"ui-proof-mainstream-baseline/ui-motion-performance.json"
            valid=json.loads(path.read_text())
            for changes in ({"renderer_pipelines":["Pipeline=Skia (Vulkan)"]},
                            {"listener_dropped":1},
                            {"frame_ns":[200_000_000]*12},
                            {"p95_ms":121.0},
                            {"very_slow_frames":3}):
                with self.subTest(changes=changes):
                    self.write(path,dict(valid,**changes))
                    with self.assertRaises(ValueError): validate(root,"head")
            self.write(path,valid)
            self.assertEqual(4,validate(root,"head")["device_configurations"])

    def test_matrix_rejects_different_apk_bytes(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d); self.build(root)
            path=root/"ui-proof-ultra-baseline/ui-baseline-diagnosis.json"
            data=json.loads(path.read_text())
            data["tested_apk_sha256"]["debug/app-debug.apk"]="c"*64
            self.write(path,data)
            with self.assertRaises(ValueError): validate(root,"head")
