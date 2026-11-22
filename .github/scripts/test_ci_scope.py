#!/usr/bin/env python3
"""Regression checks for the 111 Android CI execution graph."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ROOT / ".github" / "workflows"


def workflow(name: str) -> str:
    return (WORKFLOWS / name).read_text(encoding="utf-8")


class AndroidCIScopeTests(unittest.TestCase):
    def test_unrelated_upstream_files_do_not_trigger_ci(self):
        for name in ("build-native-codex.yml", "check-android-ui.yml",
                     "rehearse-android-upgrade.yml"):
            with self.subTest(name=name):
                contents = workflow(name)
                self.assertNotIn('"upstream/codex/**"', contents)
                self.assertNotIn('"CODEX_UPSTREAM_COMMIT"', contents)

    def test_native_build_tracks_rust_and_parent_cargo_inputs(self):
        contents = workflow("build-native-codex.yml")
        self.assertGreaterEqual(contents.count('"upstream/codex/codex-rs/**"'), 2)
        self.assertGreaterEqual(contents.count('"upstream/codex/.cargo/**"'), 2)
        self.assertIn("--abis arm64-v8a", contents)
        self.assertNotIn('--branch "${{ github.head_ref || github.ref_name }}"', contents)

    def test_android_build_and_ui_share_unit_test_gate(self):
        build = workflow("build-android.yml")
        ui = workflow("check-android-ui.yml")
        self.assertNotIn(":app:testDebugUnitTest", build)
        self.assertIn(":app:testDebugUnitTest", ui)
        self.assertIn(":app:assembleDebug", build)
        self.assertIn(":app:assembleDebugAndroidTest", build)
        self.assertIn("test_native_reuse.py", ui)

    def test_import_requires_manual_action_and_uses_reviewed_pr(self):
        contents = workflow("import-codex.yml")
        self.assertIn("  workflow_dispatch:", contents)
        self.assertNotIn("  push:", contents)
        self.assertIn("gh pr create --base main", contents)
        self.assertNotIn("git push origin HEAD:main", contents)

    def test_release_only_observes_main_and_checks_both_gates(self):
        contents = workflow("release.yml")
        self.assertIn("    branches: [main]", contents)
        self.assertIn("github.event.workflow_run.head_branch == 'main'", contents)
        self.assertIn("github.event_name == 'pull_request' || github.event_name == 'workflow_dispatch'", contents)
        self.assertIn("needs.preflight.result == 'success'", contents)
        self.assertIn("build-android.yml check-android-ui.yml", contents)
        self.assertNotIn(":app:testDebugUnitTest", contents)

    def test_git_abi_validation_has_one_maintained_implementation(self):
        self.assertFalse((WORKFLOWS / "verify-android-git.yml").exists())
        self.assertIn("bash android-app/tools/build_android_git.sh",
                      workflow("build-native-codex.yml"))
        self.assertIn("readelf -l", workflow("build-native-codex.yml"))


if __name__ == "__main__":
    unittest.main()
