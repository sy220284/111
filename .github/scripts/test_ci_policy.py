import unittest
from unittest.mock import patch
from pathlib import Path
from ci_policy import plan, maintenance_only, matches, changed

class PolicyTests(unittest.TestCase):
    def test_unknown_is_full(self):
        for path in ["new-file", "android-app/app/src/main/Main.kt", ".github/release-version", "upstream/codex/codex-rs/core/src/lib.rs"]:
            self.assertTrue(plan([path])["full"])
    def test_only_known_maintenance_is_exempt(self):
        self.assertTrue(maintenance_only(["AGENTS.md", "README.md", "docs/普通说明.md"]))
        self.assertFalse(maintenance_only(["AGENTS.md", "unknown"]))
        self.assertFalse(plan(["AGENTS.md"])["full"])
        self.assertTrue(plan(["AGENTS.md"], force=True)["full"])
    def test_user_toolchain_distribution_does_not_trigger_build_or_release(self):
        for path in (".github/workflows/dev-toolchain.yml",".github/workflows/prepare-host-toolchain.yml",
                     ".github/workflows/prepare-aux-toolchain.yml","tools/dev/ci_host.py","tools/dev/auxiliary.py","tools/dev/new-user-tool.py","tools/dev/run-build-offline.sh"):
            self.assertFalse(plan([path])["full"],path)
            self.assertFalse(matches(path,"release"),path)
        for path in ("tools/runtime/runtime-lock.json","android-app/tools/prepare_android_v8.sh","tools/build/native_toolchain.py","tools/build/native-toolchain.json"):
            self.assertTrue(plan([path])["full"],path)

    def test_release_inputs(self):
        self.assertTrue(matches("android-app/tools/prepare_prebuilt_developer_runtime.py", "release"))
        self.assertTrue(matches(".github/release-version", "release"))
        self.assertFalse(matches("android-app/app/src/test/Test.kt", "release"))
    def test_native_and_runtime_changes_enable_real_core(self):
        for path in ["upstream/codex/codex-rs/core/src/lib.rs", "android-app/tools/prepare_android_mxc.py", "android-app/app/src/main/java/io/github/sy220284/codexandroid/CodexConnection.kt"]:
            self.assertTrue(plan([path])["runtime"])
        self.assertTrue(plan(["android-app/app/src/main/java/io/github/sy220284/codexandroid/VisualSystem.kt"])["runtime"])
        self.assertFalse(plan(["AGENTS.md"])["runtime"])

    def test_nonproduct_changes_only_run_basic_checks(self):
        paths = ["docs/设计系统.md", "docs/ui-assets/a.svg", ".github/workflows/ci.yml",
                 ".github/workflows/build-native-codex.yml", ".github/actions/ci-android/action.yml",
                 ".github/scripts/prepare-release-assets.sh", ".github/scripts/ci_policy.py",
                 ".github/ci/scope.json", "tools/build/component-bundle.py",
                 "android-app/tools/device-matrix.json", "android-app/tools/device_matrix.py",
                 ".github/scripts/run-android-runtime-tests.sh", "android-app/tools/check_architecture_boundaries.py",
                 "android-app/tools/run_static_audit.py", "android-app/tools/test_run_static_audit.py",
                 "android-app/app/src/androidTest/java/Test.kt", "android-app/app/src/test/java/Test.kt"]
        for path in paths:
            with self.subTest(path=path):
                self.assertFalse(plan([path])["runtime"])
                self.assertFalse(plan([path])["full"])
        self.assertFalse(plan(paths)["full"])
        self.assertTrue(plan(paths + ["android-app/app/src/main/Main.kt"])["full"])
        self.assertTrue(plan(paths + ["upstream/codex/codex-rs/core/src/lib.rs"])["runtime"])
        self.assertTrue(plan(paths, force=True)["full"])
        self.assertTrue(plan(paths, runtime=True)["full"])
        self.assertTrue(plan(["android-app/tools/prepare_android_remote_delivery.py"])["full"])
        self.assertTrue(plan(["android-app/tools/developer_tool_launcher.c"])["full"])

    def test_security_boundary_changes_trigger_real_runtime(self):
        base = "android-app/app/src/main/java/io/github/sy220284/codexandroid/"
        for name in ["BackupDataPolicy.kt", "LocalDataArchive.kt", "LocalRestoreTransaction.kt",
                     "TerminalSessionContract.kt", "TerminalCommandPolicy.kt", "DeveloperToolStatus.kt",
                     "RealtimeVoiceController.kt", "DomesticProviderCredentials.kt"]:
            self.assertTrue(plan([base + name])["runtime"], name)

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

    def test_diagnostic_candidates_use_current_registry_without_reducing_full_acceptance(self):
        from ci_policy import diagnostic_candidates
        root=Path(__file__).resolve().parents[2]
        path="android-app/app/src/main/java/io/github/sy220284/codexandroid/SidebarSessions.kt"
        report=diagnostic_candidates([path],root)
        self.assertIn("threads",report["routes"])
        self.assertIn("sidebar",report["subpages"])
        self.assertTrue(any(x["method"]=="sidebarUsesRealThreadListAndFiltersWithoutLosingCurrentSelection" for x in report["suggested_methods"]))
        self.assertTrue(plan([path])["full"])
        self.assertEqual([],diagnostic_candidates(["new-file"],root)["suggested_methods"])
