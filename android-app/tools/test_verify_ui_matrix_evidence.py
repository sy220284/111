import json
import tempfile
import unittest
from pathlib import Path
from device_matrix import CONFIG, MODES, PROFILES, dimensions, INDEPENDENT_IDS, independent_device
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
                    missing_screens=[], screenshot_collection_exit=0, run_id=profile+mode,
                    tests=len(ids), test_ids=ids))
                self.write(folder/"ui-visual-evidence/report.json", {"status":"baseline_pending","structural_errors":[]})
                if mode=="baseline":
                    self.write(folder/"ui-motion-diagnosis.json",{"status":"passed","tests":1})
                    self.write(folder/"ui-motion-performance.json",{"frames":12})
                    if PROFILES[profile]["self_update"]:
                        self.write(folder/"self-update-open.json",{"status":"passed"})
    def write(self,path,obj):
        path.write_text(json.dumps(obj))
    def test_complete_six_mode_matrix(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d); self.build(root)
            self.assertEqual((CONFIG["minimum_ui_tests"]-len(INDEPENDENT_IDS))*6+len(INDEPENDENT_IDS),validate(root,"head")["executions"])
    def test_wrong_test_set_same_count_is_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d); self.build(root)
            path=root/"ui-proof-compact-adaptive/ui-adaptive-diagnosis.json"
            data=json.loads(path.read_text()); data["test_ids"][-1]="Different#test"; self.write(path,data)
            with self.assertRaises(ValueError): validate(root,"head")
    def test_missing_and_stale_proof_are_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d); self.build(root)
            with self.assertRaises(ValueError): validate(root,"different")
            (root/"ui-proof-compact-adaptive").rename(root/"unexpected")
            with self.assertRaises(ValueError): validate(root,"head")
    def test_visual_and_motion_failures_block(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d); self.build(root)
            self.write(root/"ui-proof-compact-baseline/ui-motion-performance.json",{"frames":0})
            with self.assertRaises(ValueError): validate(root,"head")

    def test_installer_contract_must_run_once_on_designated_device(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d); self.build(root)
            path=root/"ui-proof-mainstream-baseline/ui-baseline-diagnosis.json"
            data=json.loads(path.read_text()); data["test_ids"][-1]="Example#fake"; self.write(path,data)
            with self.assertRaises(ValueError): validate(root,"head")
