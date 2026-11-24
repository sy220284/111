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
        self.assertIn('- ".github/workflows/build-android.yml"', contents)
        self.assertNotIn('--branch "${{ github.head_ref || github.ref_name }}"', contents)

    def test_android_ui_changes_get_same_commit_full_arm64_candidate(self):
        native = workflow("build-native-codex.yml")
        pr_paths = native.split("  pull_request:", 1)[1].split("  workflow_dispatch:", 1)[0]
        for path in ("android-app/app/**", "android-app/build.gradle.kts",
                     "android-app/settings.gradle.kts", "android-app/gradle.properties",
                     ".github/scripts/prepare-android-patcher.sh",
                     ".github/scripts/create-android-update-fixture.sh",
                     ".github/scripts/audit_apk_contents.py"):
            with self.subTest(path=path):
                self.assertIn(f'- "{path}"', pr_paths)
        self.assertIn("github.event.pull_request.head.repo.full_name == github.repository", native)
        self.assertIn("needs.reuse.outputs.reuse == 'true' || needs.native.result == 'success'", native)
        self.assertIn('-f expected_sha="$PR_HEAD_SHA"', native)

    def test_authoritative_protocol_ledger_is_checked_on_every_change(self):
        ui = workflow("check-android-ui.yml")
        ledger = "docs/接口协议动作清单与落地验收台账-20261122.md"
        self.assertEqual(2, ui.count(f'- "{ledger}"'))
        self.assertIn(f"/{ledger}", ui)
        self.assertIn("sync_ui_protocol_docs.py --check", ui)

    def test_android_build_and_ui_share_unit_test_gate(self):
        build = workflow("build-android.yml")
        ui = workflow("check-android-ui.yml")
        self.assertNotIn(":app:testDebugUnitTest", build)
        self.assertIn(":app:testDebugUnitTest", ui)
        self.assertIn(":app:assembleDebug", build)
        self.assertIn(":app:assembleDebugAndroidTest", build)
        self.assertIn("test_native_reuse.py", ui)

    def test_stale_pr_native_checks_cannot_retrigger_latest_apk(self):
        native = workflow("build-native-codex.yml")
        android = workflow("build-android.yml")
        self.assertIn('CURRENT_HEAD="$(gh api', native)
        self.assertIn('if [ "$CURRENT_HEAD" != "$PR_HEAD_SHA" ]; then', native)
        self.assertIn('-f expected_sha="$PR_HEAD_SHA"', native)
        self.assertIn("inputs.expected_sha == github.sha", android)
        self.assertIn("inputs.expected_sha == ''", android)

    def test_merged_push_waits_for_native_only_when_relevant(self):
        android = workflow("build-android.yml")
        self.assertIn("decide_android_build.py", android)
        self.assertIn("needs.preflight.outputs.should_build == 'true'", android)
        self.assertIn("github.event.workflow_run.head_sha == github.sha", android)
        self.assertIn("if: always() && needs.build.result == 'success'", android)
        self.assertIn("ARM64 手机安装包二次核验", android)
        self.assertIn("github.event.workflow_run.head_repository.full_name == github.repository", android)

    def test_cleanup_is_reusable_and_only_called_after_real_release(self):
        cleanup = workflow("cleanup-old-releases.yml")
        release = workflow("release.yml")
        self.assertIn("  workflow_call:", cleanup)
        self.assertIn("  workflow_dispatch:", cleanup)
        self.assertNotIn("  release:", cleanup)
        self.assertIn("needs: release", release)
        self.assertIn("always() && needs.release.result == 'success'", release)

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
        self.assertIn("verify_release_acceptance.py", contents)
        self.assertIn("always() && needs.release.result == 'success'", contents)
        self.assertIn("always() && needs.check-release.result == 'success'", contents)
        self.assertIn("uses: ./.github/workflows/cleanup-old-releases.yml", contents)
        self.assertNotIn(":app:testDebugUnitTest", contents)
        self.assertIn("release_change_gate.py", contents)

    def test_git_abi_validation_has_one_maintained_implementation(self):
        self.assertFalse((WORKFLOWS / "verify-android-git.yml").exists())
        self.assertIn('bash "$GITHUB_WORKSPACE/android-app/tools/build_android_git.sh"',
                      workflow("build-native-codex.yml"))
        self.assertIn("readelf -l", workflow("build-native-codex.yml"))


if __name__ == "__main__":
    unittest.main()
