import unittest
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
W = ROOT / ".github/workflows"

class UnifiedScopeTests(unittest.TestCase):
    def test_single_ci_entry_and_complete_gate(self):
        ci = (W / "ci.yml").read_text()
        for key in ("plan:", "host-android:", "host-wheelhouse:", "host-emulator:", "host-patcher:", "native:", "runtime-arm64:", "runtime-x86:", "static-ui:", "verify-ui:", "ui-emulator:", "ui-collect:", "ui-evidence:", "build:", "runtime-test:", "verify-apk:", "preflight:", "release-signing-smoke:", "compatibility:", "quality:"):
            self.assertIn("  " + key, ci)
        self.assertIn("name: 111 合并验收", ci)
        self.assertIn("python3 .github/scripts/ci_gate.py", ci)
        for old in ("build-android.yml", "check-android-ui.yml", "rehearse-android-upgrade.yml", "cleanup-old-releases.yml"):
            self.assertFalse((W / old).exists())
    def test_ui_modes_and_independent_integrity_can_run_in_parallel(self):
        ci = (W / "ci.yml").read_text()
        self.assertIn("needs: [plan, verify-ui, host-android, host-emulator]", ci)
        self.assertIn("--mode ${{ matrix.mode }}", ci)
        self.assertIn("pattern: ui-proof-*", ci)
        self.assertIn("needs: [plan, ui-emulator, host-wheelhouse]", ci)
        self.assertIn("needs: [plan, ui-collect]", ci)
        self.assertIn("needs.ui-collect.result == 'failure'", ci)
        self.assertIn("--shard ${{ matrix.shard }}", ci)
        self.assertIn("pattern: ui-shard-${{ matrix.profile }}-${{ matrix.mode }}-*", ci)
        self.assertEqual(1, ci.count("needs: [plan, static-ui, preflight, host-android]"))
        self.assertIn("needs.ui-emulator.result == 'failure'", ci)
        self.assertIn("保存供全矩阵核对的小型验收凭证\n        if: always()", ci)
        runtime = ci.split("\n  runtime-test:", 1)[1].split("\n  verify-apk:", 1)[0]
        verify_apk = ci.split("\n  verify-apk:", 1)[1].split("\n  preflight:", 1)[0]
        self.assertIn("needs: [plan, native, runtime-x86, host-android, host-emulator, host-patcher]", runtime)
        self.assertIn("needs: [plan, build]", verify_apk)
        self.assertNotIn("needs.runtime-test", verify_apk)
    def test_host_toolchain_is_consumed_by_actual_builds(self):
        for name in ("ci.yml", "release.yml", "build-native-codex.yml"):
            source=(W/name).read_text()
            self.assertIn("./.github/actions/install-host-toolchain",source)
            self.assertNotIn("actions/setup-java@",source)
            self.assertNotIn("gradle/actions/setup-gradle@",source)
        self.assertIn("cargo build --locked --offline --release",(W/"build-native-codex.yml").read_text())
        self.assertNotIn("reactivecircus/android-emulator-runner@",(W/"ci.yml").read_text())
        self.assertIn("./.github/actions/run-emulator",(W/"ci.yml").read_text())
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
