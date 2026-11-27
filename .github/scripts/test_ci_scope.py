import unittest
import subprocess
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
W = ROOT / ".github/workflows"

class UnifiedScopeTests(unittest.TestCase):
    def test_single_ci_entry_and_complete_gate(self):
        ci = (W / "ci.yml").read_text()
        for key in ("plan:", "native:", "runtime-arm64:", "runtime-x86:", "static-ui:", "verify-ui:", "ui-emulator:", "ui-collect:", "ui-evidence:", "build:", "runtime-test:", "verify-apk:", "preflight:", "release-signing-smoke:", "compatibility:", "quality:"):
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
        self.assertIn("needs: [plan, ui-collect]", ci)
        self.assertIn("needs.ui-collect.result == 'failure'", ci)
        self.assertIn("--shard ${{ matrix.shard }}", ci)
        self.assertIn("pattern: ui-shard-${{ matrix.profile }}-${{ matrix.mode }}-*", ci)
        self.assertEqual(3, ci.count("needs: [plan, static-ui, preflight]"))
        self.assertIn("needs.ui-emulator.result == 'failure'", ci)
        self.assertIn("保存供全矩阵核对的小型验收凭证\n        if: ${{ !cancelled() }}", ci)
        runtime = ci.split("\n  runtime-test:", 1)[1].split("\n  verify-apk:", 1)[0]
        verify_apk = ci.split("\n  verify-apk:", 1)[1].split("\n  preflight:", 1)[0]
        self.assertIn("needs: [plan, native, runtime-x86]", runtime)
        self.assertIn("needs: [plan, build]", verify_apk)
        self.assertNotIn("needs.runtime-test", verify_apk)
    def test_ui_emulator_reads_the_physical_baseline_before_device_tests(self):
        ci = (W / "ci.yml").read_text()
        ui = ci.split("\n  ui-emulator:", 1)[1].split("\n  ui-collect:", 1)[0]
        for write in ("adb shell wm size reset", "adb shell wm density reset",
                      "adb shell settings put system font_scale"):
            self.assertNotIn(write, ui)
        before_motion = ui.split("python3 android-app/tools/run_ui_motion_checks.py", 1)[0]
        for read in ("adb shell wm size", "adb shell wm density",
                     "adb shell settings get system font_scale"):
            self.assertIn(read, before_motion)
        self.assertIn("null|1.0", before_motion)

    def test_emulator_boot_dismisses_keyguard_without_injecting_input_and_checks_home_focus(self):
        script = (ROOT / "tools/build/run-emulator.sh").read_text()
        self.assertNotIn("input keyevent 82", script)
        self.assertIn("device_shell wm dismiss-keyguard", script)
        self.assertIn("Android HOME focused and stable", script)
        self.assertLess(script.index("Android HOME focused and stable"), script.index('bash -euo pipefail -c "$EMULATOR_SCRIPT"'))
        helper = "home_focus_ready() {" + script.split("home_focus_ready() {", 1)[1].split("\n}\n", 1)[0] + "\n}\n"
        command = helper + 'home_focus_ready "$1" "$2"'
        package = "com.google.android.apps.nexuslauncher"
        for focus, expected in (
            ("Window{a u0 " + package + "/.NexusLauncherActivity}", 0),
            ("null", 1),
            ("Window{b u0 Application Not Responding: " + package + "}", 1),
            ("Window{c u0 com.android.systemui/.Keyguard}", 1),
            ("Window{d u0 " + package + ".other/.Activity}", 1),
        ):
            with self.subTest(focus=focus):
                result = subprocess.run(["bash", "-c", command, "test", "  mCurrentFocus=" + focus, package],
                                        capture_output=True, text=True)
                self.assertEqual(expected, result.returncode, result.stderr)

    def test_user_toolchain_is_not_consumed_by_ci(self):
        for name in ("ci.yml","release.yml","build-native-codex.yml"):
            source=(W/name).read_text()
            for obsolete in ("install-host-toolchain","prepare-host-toolchain","prepare-aux-toolchain","ci_host.py","check-ci-toolchain.sh","111-toolchain-","android_descriptor"):
                self.assertNotIn(obsolete,source)
            self.assertIn("./.github/actions/ci-android",source)
        self.assertIn("cargo build --locked --offline --release",(W/"build-native-codex.yml").read_text())
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
