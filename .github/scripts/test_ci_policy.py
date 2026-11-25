import unittest
from ci_policy import plan, maintenance_only, matches

class PolicyTests(unittest.TestCase):
    def test_unknown_is_full(self):
        for path in ["new-file", "android-app/app/src/main/Main.kt", ".github/release-version", ".github/scripts/prepare-release-assets.sh", "docs/设计系统.md"]:
            self.assertTrue(plan([path])["full"])
    def test_only_known_maintenance_is_exempt(self):
        self.assertTrue(maintenance_only(["AGENTS.md", "README.md"]))
        self.assertFalse(maintenance_only(["AGENTS.md", "unknown"]))
        self.assertFalse(plan(["AGENTS.md"])["full"])
        self.assertTrue(plan(["AGENTS.md"], force=True)["full"])
    def test_release_inputs(self):
        self.assertTrue(matches("android-app/tools/prepare_prebuilt_developer_runtime.py", "release"))
        self.assertTrue(matches(".github/release-version", "release"))
        self.assertFalse(matches("android-app/app/src/test/Test.kt", "release"))
    def test_runtime_forces_full(self):
        self.assertTrue(plan([], runtime=True)["full"])
        self.assertTrue(plan([], runtime=True)["runtime"])
