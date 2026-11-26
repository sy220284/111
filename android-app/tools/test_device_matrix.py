import unittest
import tempfile
from pathlib import Path
from device_matrix import verify_resources, CONFIG, PROFILES, dimensions, matrix, job_name, configure_avd, MODES, SHARDS, shard_matrix, shard_classes, shard_job_name

class DeviceMatrixTests(unittest.TestCase):
    def test_two_distinct_current_phones_and_one_update_owner(self):
        self.assertEqual(len(CONFIG["profiles"]), len(PROFILES))
        self.assertEqual({"mainstream", "ultra"}, set(PROFILES))
        self.assertEqual(1, sum(p["self_update"] for p in PROFILES.values()))
        self.assertEqual(set(PROFILES), {p["profile"] for p in matrix()["include"]})
        self.assertEqual(4, len(matrix()["include"]))
        self.assertEqual({(p,m) for p in PROFILES for m in MODES}, {(row["profile"],row["mode"]) for row in matrix()["include"]})
        self.assertEqual(4, len({job_name(p,m) for p in PROFILES for m in MODES}))
        self.assertEqual(1, sum(row["self_update"] for row in matrix()["include"]))
        self.assertEqual(2, sum(row["motion"] for row in matrix()["include"]))
    def test_eight_distinct_shards_and_unique_specialty_owners(self):
        rows = shard_matrix()["include"]
        self.assertEqual(8, len(rows))
        self.assertEqual(8, len({shard_job_name(r["profile"], r["mode"], r["shard"]) for r in rows}))
        self.assertEqual({(p,m,s) for p in PROFILES for m in MODES for s in SHARDS},
                         {(r["profile"],r["mode"],r["shard"]) for r in rows})
        self.assertEqual(2, sum(r["motion"] for r in rows))
        self.assertEqual(1, sum(r["self_update"] for r in rows))
        for p in PROFILES:
            for m in MODES:
                a,b = (set(shard_classes(p,m,s)) for s in SHARDS)
                self.assertFalse(a & b)
                self.assertEqual(7 if p == "mainstream" and m == "baseline" else 6, len(a | b))
                self.assertIn("VideoAttachmentPreparationInstrumentedTest", b)

    def test_high_resolution_and_adaptive_display(self):
        for profile in PROFILES:
            b = dimensions(profile, "baseline")
            a = dimensions(profile, "adaptive")
            self.assertGreaterEqual(b["width_px"], 1080)
            self.assertGreaterEqual(b["height_px"], 2340)
            self.assertGreaterEqual(b["density_dpi"], 400)
            self.assertGreater(a["font_scale"], b["font_scale"])
            self.assertGreaterEqual(a["width_px"] * 160 / a["density_dpi"], 360)
        self.assertEqual(424, dimensions("mainstream", "adaptive")["width_px"] * 160 / dimensions("mainstream", "adaptive")["density_dpi"])
        self.assertNotIn("compact", PROFILES)
        with self.assertRaises(KeyError):
            dimensions("compact", "baseline")
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
        self.assertEqual("8192M", CONFIG["ram"])
        self.assertEqual(4, CONFIG["cores"])
        self.assertEqual("8192M", CONFIG["disk"])
        self.assertEqual(4, CONFIG["mesa_threads"])

    def test_actual_guest_full_resources(self):
        self.assertEqual("passed", verify_resources("4", "MemTotal:       7864320 kB\n")["status"])
        self.assertEqual("failed", verify_resources("2", "MemTotal:       7864320 kB\n")["status"])
        self.assertEqual("failed", verify_resources("4", "MemTotal:       3932160 kB\n")["status"])
        with self.assertRaises(ValueError):
            verify_resources("4", "MemFree: 123 kB\n")

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
