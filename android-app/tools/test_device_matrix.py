import unittest
import tempfile
from pathlib import Path
from device_matrix import CONFIG, PROFILES, dimensions, matrix, job_name, configure_avd, MODES, SHARDS, shard_matrix, shard_classes, shard_job_name

class DeviceMatrixTests(unittest.TestCase):
    def test_three_distinct_current_phones_and_one_update_owner(self):
        self.assertEqual(len(CONFIG["profiles"]), len(PROFILES))
        self.assertEqual(3, len(PROFILES))
        self.assertEqual(1, sum(p["self_update"] for p in PROFILES.values()))
        self.assertEqual(set(PROFILES), {p["profile"] for p in matrix()["include"]})
        self.assertEqual(6, len(matrix()["include"]))
        self.assertEqual({(p,m) for p in PROFILES for m in MODES}, {(row["profile"],row["mode"]) for row in matrix()["include"]})
        self.assertEqual(6, len({job_name(p,m) for p in PROFILES for m in MODES}))
        self.assertEqual(1, sum(row["self_update"] for row in matrix()["include"]))
        self.assertEqual(3, sum(row["motion"] for row in matrix()["include"]))
    def test_twelve_distinct_shards_and_unique_specialty_owners(self):
        rows = shard_matrix()["include"]
        self.assertEqual(12, len(rows))
        self.assertEqual(12, len({shard_job_name(r["profile"], r["mode"], r["shard"]) for r in rows}))
        self.assertEqual({(p,m,s) for p in PROFILES for m in MODES for s in SHARDS},
                         {(r["profile"],r["mode"],r["shard"]) for r in rows})
        self.assertEqual(3, sum(r["motion"] for r in rows))
        self.assertEqual(1, sum(r["self_update"] for r in rows))
        for p in PROFILES:
            for m in MODES:
                a,b = (set(shard_classes(p,m,s)) for s in SHARDS)
                self.assertFalse(a & b)
                self.assertEqual(6 if p == "mainstream" and m == "baseline" else 5, len(a | b))

    def test_high_resolution_and_adaptive_narrow_screen(self):
        for profile in PROFILES:
            b = dimensions(profile, "baseline")
            a = dimensions(profile, "adaptive")
            self.assertGreaterEqual(b["width_px"], 1080)
            self.assertGreaterEqual(b["height_px"], 2340)
            self.assertGreaterEqual(b["density_dpi"], 400)
            self.assertGreater(a["font_scale"], b["font_scale"])
            self.assertGreaterEqual(a["width_px"] * 160 / a["density_dpi"], 360)
        self.assertEqual(360, dimensions("compact", "adaptive")["width_px"] * 160 / dimensions("compact", "adaptive")["density_dpi"])
    def test_android17_16kb_image(self):
        self.assertEqual(37, CONFIG["api"])
        self.assertEqual(17, CONFIG["android_release"])
        self.assertEqual(16384, CONFIG["page_size"])
        self.assertEqual("google_apis_ps16k", CONFIG["target"])
        self.assertIn("-gpu swiftshader ", CONFIG["emulator_options"])
        self.assertNotIn("swiftshader_indirect", CONFIG["emulator_options"])
        self.assertEqual("37.2", CONFIG["system_image_api"])
        self.assertEqual("35", CONFIG["sdk_platform"])
        self.assertIn(CONFIG["runtime_profile"], PROFILES)
        self.assertGreaterEqual(CONFIG["minimum_ui_tests"], 150)
        self.assertGreaterEqual(int(CONFIG["ram"].removesuffix("M")), 4096)

    def test_avd_boot_geometry_is_unique_and_unrelated_settings_preserved(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "config.ini"
            path.write_text("hw.lcd.width=480\nhw.lcd.width=720\nhw.cpu.ncore=2\nskin.path=old\n")
            configure_avd(path, "ultra")
            lines = path.read_text().splitlines()
            self.assertEqual(["hw.lcd.width=1440"], [line for line in lines if line.startswith("hw.lcd.width=")])
            self.assertIn("hw.lcd.height=3120", lines)
            self.assertIn("hw.lcd.density=520", lines)
            self.assertIn("hw.cpu.ncore=2", lines)
            self.assertIn("skin.path=_no_skin", lines)
