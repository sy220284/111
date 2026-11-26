import unittest
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
W = ROOT / ".github/workflows"

class UnifiedScopeTests(unittest.TestCase):
    def test_single_ci_entry_and_complete_gate(self):
        ci = (W / "ci.yml").read_text()
        for key in ("plan:", "native:", "static-ui:", "verify-ui:", "ui-emulator:", "ui-evidence:", "build:", "runtime-test:", "verify-apk:", "preflight:", "release-signing-smoke:", "compatibility:", "quality:"):
            self.assertIn("  " + key, ci)
        self.assertIn("name: 111 合并验收", ci)
        self.assertIn("python3 .github/scripts/ci_gate.py", ci)
        for old in ("build-android.yml", "check-android-ui.yml", "rehearse-android-upgrade.yml", "cleanup-old-releases.yml"):
            self.assertFalse((W / old).exists())
    def test_ui_modes_and_independent_integrity_can_run_in_parallel(self):
        ci = (W / "ci.yml").read_text()
        self.assertIn("needs: [plan, verify-ui]", ci)
        self.assertIn("--mode ${{ matrix.mode }}", ci)
        self.assertIn("pattern: ui-proof-*", ci)
        self.assertIn("needs: [plan, ui-emulator]", ci)
        self.assertEqual(3, ci.count("needs: [plan, static-ui, preflight]"))
        self.assertIn("needs.ui-emulator.result == 'failure'", ci)
        self.assertIn("保存供全矩阵核对的小型验收凭证\n        if: always()", ci)
        runtime = ci.split("\n  runtime-test:", 1)[1].split("\n  verify-apk:", 1)[0]
        verify_apk = ci.split("\n  verify-apk:", 1)[1].split("\n  preflight:", 1)[0]
        self.assertIn("needs: [plan, native]", runtime)
        self.assertIn("needs: [plan, build]", verify_apk)
        self.assertNotIn("needs.runtime-test", verify_apk)
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
