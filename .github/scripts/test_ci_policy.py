import unittest
from unittest.mock import patch
from pathlib import Path
from ci_policy import plan, maintenance_only, matches, changed

class PolicyTests(unittest.TestCase):
    def test_unknown_is_full(self):
        for path in ["new-file", "android-app/app/src/main/Main.kt", ".github/release-version", ".github/scripts/prepare-release-assets.sh", "docs/设计系统.md", "docs/ui-assets/a.svg", ".github/workflows/ci.yml", "upstream/codex/codex-rs/core/src/lib.rs"]:
            self.assertTrue(plan([path])["full"])
    def test_only_known_maintenance_is_exempt(self):
        self.assertTrue(maintenance_only(["AGENTS.md", "README.md", "docs/普通说明.md"]))
        self.assertFalse(maintenance_only(["AGENTS.md", "unknown"]))
        self.assertFalse(plan(["AGENTS.md"])["full"])
        self.assertTrue(plan(["AGENTS.md"], force=True)["full"])
    def test_release_inputs(self):
        self.assertTrue(matches("android-app/tools/prepare_prebuilt_developer_runtime.py", "release"))
        self.assertTrue(matches(".github/release-version", "release"))
        self.assertFalse(matches("android-app/app/src/test/Test.kt", "release"))
    def test_native_and_runtime_changes_enable_real_core(self):
        for path in ["upstream/codex/codex-rs/core/src/lib.rs", "android-app/tools/prepare_android_mxc.py", "android-app/app/src/main/java/io/github/sy220284/codexandroid/CodexConnection.kt"]:
            self.assertTrue(plan([path])["runtime"])
        self.assertFalse(plan(["android-app/app/src/main/java/io/github/sy220284/codexandroid/VisualSystem.kt"])["runtime"])

    def test_runtime_forces_full(self):
        self.assertTrue(plan([], runtime=True)["full"])
        self.assertTrue(plan([], runtime=True)["runtime"])
    def test_compare_uses_both_commits_and_detects_deletes(self):
        a, b = "a" * 40, "b" * 40
        with patch("ci_policy.subprocess.check_output", return_value=b"docs/old.md\0android-app/app/src/main/Removed.kt\0") as call:
            self.assertTrue(plan(changed(a, b))["full"])
            self.assertIn("--no-renames", call.call_args.args[0])
            self.assertEqual(call.call_args.args[0][-3:], [a, b, "--"])
    def test_invalid_sha_is_error(self):
        with self.assertRaises(ValueError):
            changed("broken", "a" * 40)

    def test_pr_supersession_preserves_independent_acceptance(self):
        workflow = (Path(__file__).resolve().parents[1] / "workflows/ci.yml").read_text()
        concurrency = workflow.split("\nconcurrency:\n", 1)[1].split("\njobs:\n", 1)[0]
        self.assertIn("group: ci-${{ github.event.pull_request.number || github.ref }}-${{ github.event_name == 'pull_request' && 'latest' || github.sha }}", concurrency)
        self.assertIn("cancel-in-progress: ${{ github.event_name == 'pull_request' }}", concurrency)
        self.assertNotIn("github.event.pull_request.head.sha", concurrency)
        self.assertIn("ref: ${{ github.event.pull_request.head.sha || github.sha }}", workflow)
        self.assertIn("python3 .github/scripts/ci_gate.py", workflow)
