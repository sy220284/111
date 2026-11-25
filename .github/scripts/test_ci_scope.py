import unittest
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
W = ROOT / ".github/workflows"

class UnifiedScopeTests(unittest.TestCase):
    def test_single_ci_entry_and_complete_gate(self):
        ci = (W / "ci.yml").read_text()
        for key in ("plan:", "native:", "verify-ui:", "ui-emulator:", "build:", "runtime-test:", "verify-apk:", "preflight:", "release-signing-smoke:", "compatibility:", "quality:"):
            self.assertIn("  " + key, ci)
        self.assertIn("name: 111 合并验收", ci)
        self.assertIn("python3 .github/scripts/ci_gate.py", ci)
        for old in ("build-android.yml", "check-android-ui.yml", "rehearse-android-upgrade.yml", "cleanup-old-releases.yml"):
            self.assertFalse((W / old).exists())
    def test_release_consumes_only_unified_main_gate(self):
        release = (W / "release.yml").read_text()
        self.assertIn("workflows: ['111 统一质量验收']", release)
        self.assertIn("needs.device.result == 'success'", release)
        self.assertIn("publish-verified-release.sh", release)
        self.assertIn("needs.publish.result == 'success'", release)
    def test_native_is_reusable_and_dispatch_is_not_async(self):
        native = (W / "build-native-codex.yml").read_text()
        self.assertIn("workflow_call:", native)
        self.assertIn("native_index.py create", native)
        self.assertNotIn("gh workflow run", native)
