import unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]

class ConsolidationTests(unittest.TestCase):
    def test_user_distribution_is_manual_and_separate(self):
        dev=(ROOT/".github/workflows/dev-toolchain.yml").read_text()
        self.assertIn("workflow_dispatch:",dev)
        self.assertNotIn("  push:",dev)
        for p in ("ci.yml","release.yml","build-native-codex.yml"):
            source=(ROOT/".github/workflows"/p).read_text()
            for forbidden in ("prepare-host-toolchain","prepare-aux-toolchain","install-host-toolchain","check-ci-toolchain.sh","111-toolchain-","host-android","host-rust","host-llvm","host-emulator","host-wheelhouse","host-patcher"):
                self.assertNotIn(forbidden,source,p)
    def test_standard_environment_uses_only_official_sources(self):
        sdk=(ROOT/".github/scripts/ci-android-sdk.sh").read_text()
        self.assertIn('if [ ! -s "$SDK/$2" ]',sdk)
        self.assertIn('--install "${packages[@]}"',sdk)
        self.assertNotIn("gh api",sdk)
        action=(ROOT/".github/actions/ci-android/action.yml").read_text()
        self.assertIn("actions/setup-java@",action)
        self.assertIn("gradle/actions/setup-gradle@",action)
        self.assertNotIn("download-artifact",action)
