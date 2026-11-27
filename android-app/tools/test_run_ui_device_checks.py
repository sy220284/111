#!/usr/bin/env python3
import unittest
import shlex
from unittest.mock import patch
from run_ui_device_checks import parse_instrumentation, assert_device, clear_device_screens, completed_test_ids, prepare_display_change, adaptive, instrumentation_command, PACKAGE, RUNNER


class DeviceEvidenceTest(unittest.TestCase):
    def test_bounded_diagnostic_retains_partial_output_and_cannot_pass(self):
        import sys
        import tempfile
        from pathlib import Path
        from run_ui_device_checks import execute_instrumentation
        command = [sys.executable, "-u", "-c", "import time; print('partial device evidence', flush=True); time.sleep(30)"]
        with tempfile.TemporaryDirectory() as folder, patch("run_ui_device_checks.instrumentation_command", return_value=command):
            path = Path(folder) / "diagnostic.log"
            result = execute_instrumentation(["Diagnostic"], path, timeout=.2)
            self.assertIn("partial device evidence", path.read_text())
            self.assertEqual("failed", result["status"])
            self.assertEqual("diagnostic_timeout", result["category"])
            self.assertEqual(0, result["tests"])

    def test_instrumentation_arguments_survive_android_shell_parsing(self):
        renderer = "Pipeline=Skia (Vulkan)"
        literal = "one 'two' ; $(echo unexpected) & three"
        command = instrumentation_command(["UiMotionPerformanceInstrumentedTest"],
                                          {"expectedRenderer": renderer, "literal": literal})
        self.assertEqual(["adb", "shell"], command[:2])
        self.assertEqual(3, len(command))
        self.assertEqual(["am", "instrument", "-w", "-r", "-e", "class",
                          PACKAGE + ".UiMotionPerformanceInstrumentedTest",
                          "-e", "expectedRenderer", renderer, "-e", "literal", literal, RUNNER],
                         shlex.split(command[2]))

    def test_display_preparation_waits_for_the_actual_resumed_activity(self):
        with patch("run_ui_device_checks.adb", side_effect=[
            "Status: ok\nComplete",
            "topResumedActivity=ActivityRecord{1 u0 com.android.launcher3/.Launcher}",
            "topResumedActivity=ActivityRecord{2 u0 io.github.sy220284.codexandroid/.MainActivity}",
        ]) as adb, patch("run_ui_device_checks.time.sleep"):
            prepare_display_change()
            self.assertEqual(("shell", "am", "start", "-W", "-n", "io.github.sy220284.codexandroid/.MainActivity"), adb.call_args_list[0].args)
            self.assertEqual(3, adb.call_count)
        with patch("run_ui_device_checks.adb", return_value="Error: denied"):
            with self.assertRaises(RuntimeError):
                prepare_display_change()
        with patch("run_ui_device_checks.adb", side_effect=["Status: ok", "topResumedActivity=com.android.launcher3/.Launcher"]), patch("run_ui_device_checks.time.monotonic", side_effect=[0, 16]):
            with self.assertRaises(RuntimeError):
                prepare_display_change()

    def test_adaptive_dedicated_device_never_changes_home_or_resizes_after_test(self):
        from unittest.mock import call
        for profile, replies, writes in (
            ("mainstream", ["Physical size: 1272x2772", "Physical density: 480", "", "1.0", ""],
             [call("shell", "wm", "size", "1272x2400"),
              call("shell", "settings", "put", "system", "font_scale", "1.3")]),
            ("ultra", ["Physical size: 1440x3120", "Physical density: 520", "1.0", ""],
             [call("shell", "settings", "put", "system", "font_scale", "1.5")]),
            ("ultra", ["Physical size: 1440x3120", "Physical density: 520", "1.5"], []),
        ):
            with self.subTest(profile=profile, writes=writes), patch("run_ui_device_checks.prepare_display_change") as prepare, patch("run_ui_device_checks.adb", side_effect=replies) as adb, patch("run_ui_device_checks.run_fresh_suite", return_value={"status": "passed"}) as suite:
                self.assertEqual({"status": "passed"}, adaptive(profile, "navigation"))
                prepare.assert_called_once()
                actual_writes = [entry for entry in adb.call_args_list
                                 if len(entry.args) == 4 and entry.args[1] == "wm" or
                                 len(entry.args) > 3 and entry.args[1:3] == ("settings", "put")]
                self.assertEqual(writes, actual_writes)
                suite.assert_called_once_with(profile, "adaptive", "ui-adaptive-result.log", "ui-screenshots-adaptive", "navigation")

    def test_anr_diagnostics_preserve_failure_and_system_event(self):
        from subprocess import CompletedProcess, TimeoutExpired
        from run_ui_device_checks import collect_anr_diagnostics, category
        with patch("run_ui_device_checks.subprocess.run", side_effect=[
            CompletedProcess([], 0, stdout="am_anr: launcher", stderr=""),
            TimeoutExpired("dumpsys", 20),
        ]) as run:
            records = collect_anr_diagnostics()
            self.assertEqual("am_anr: launcher", records[0]["output"])
            self.assertEqual(0, records[0]["exit_code"])
            self.assertIn("error", records[1])
            self.assertEqual(2, run.call_count)
        self.assertEqual("system_window_anr", category("截图被其他程序或系统窗口遮挡：x Application Not Responding: launcher"))
        self.assertEqual("ui_thread", category("Detected multithreaded access to SnapshotStateObserver"))

    def test_success_requires_positive_count_and_successful_process(self):
        self.assertEqual("passed", parse_instrumentation("OK (142 tests)\n", 0)["status"])
        self.assertEqual("failed", parse_instrumentation("OK (0 tests)\n", 0)["status"])
        self.assertEqual("failed", parse_instrumentation("OK (1 test)\n", 1)["status"])

    def test_timeout_and_thread_exception_are_reported_separately(self):
        log = ("There were 2 failures:\n"
               "1) popup(Test)\nandroidx.compose.ui.test.ComposeTimeoutException: timed out\n"
               "2) image(Test)\nandroid.view.ViewRootImpl$CalledFromWrongThreadException: expected main\n"
               "FAILURES!!!\nTests run: 142, Failures: 2\n")
        result = parse_instrumentation(log, 0)
        self.assertEqual("failed", result["status"])
        self.assertEqual(142, result["tests"])
        self.assertEqual(["state_or_layout_timeout", "ui_thread"], [f["category"] for f in result["failures"]])

    def test_assertions_and_aborted_runner_are_not_success(self):
        result = parse_instrumentation("1) model(Test)\njava.lang.AssertionError: missing\nTests run: 1, Failures: 1\n", 0)
        self.assertEqual("interaction_assertion", result["failures"][0]["category"])
        self.assertEqual("runner_or_device", parse_instrumentation("INSTRUMENTATION_RESULT: shortMsg=Process crashed.\n", 0)["category"])
        self.assertEqual("failed", parse_instrumentation("OK (1 test)\nINSTRUMENTATION_FAILED: runner\n", 0)["status"])

    def test_success_text_inside_test_log_is_not_a_complete_pass(self):
        self.assertEqual("failed", parse_instrumentation("test message OK (1 test)\n", 0)["status"])
        self.assertEqual("failed", parse_instrumentation("OK (1 test)\nOK (1 test)\n", 0)["status"])

    def test_completed_test_ids_count_only_real_success_records(self):
        log = ("INSTRUMENTATION_STATUS: class=example.Test\n"
               "INSTRUMENTATION_STATUS: test=first\nINSTRUMENTATION_STATUS_CODE: 1\n"
               "INSTRUMENTATION_STATUS: class=example.Test\n"
               "INSTRUMENTATION_STATUS: test=first\nINSTRUMENTATION_STATUS_CODE: 0\n"
               "INSTRUMENTATION_STATUS: class=example.Test\n"
               "INSTRUMENTATION_STATUS: test=second\nINSTRUMENTATION_STATUS_CODE: 1\n")
        self.assertEqual(["example.Test#first"], completed_test_ids(log))
        with self.assertRaises(ValueError):
            completed_test_ids("INSTRUMENTATION_STATUS_CODE: 0\n")

    def test_actual_device_parameters_are_required(self):
        from device_matrix import dimensions, CONFIG
        spec = dimensions("mainstream", "baseline")
        replies = ["Physical size: 1272x2772", "Physical density: 480", str(CONFIG["api"]), str(CONFIG["android_release"]), str(CONFIG["page_size"]), "test/android17/fingerprint", "1.0"]
        with patch("run_ui_device_checks.adb", side_effect=replies):
            evidence = assert_device(spec, spec)
            self.assertEqual(424.0, evidence["width_dp"])
            self.assertEqual(CONFIG["api"], evidence["android_api"])
        for index, bad in [(0, "Physical size: 360x800\nOverride size: 1272x2772"), (1, "Physical density: 160\nOverride density: 440"), (2, "35"), (3, "15"), (4, "4096"), (5, ""), (6, "1.5")]:
            wrong = replies.copy()
            wrong[index] = bad
            with patch("run_ui_device_checks.adb", side_effect=wrong), self.assertRaises(RuntimeError):
                assert_device(spec, spec)

    def test_screenshot_failure_does_not_change_functional_result(self):
        import tempfile
        from run_ui_device_checks import run_fresh_suite
        from device_matrix import dimensions, shard_classes
        measured = dimensions("mainstream", "baseline")
        ids = [PACKAGE + "." + name + "#example" for name in shard_classes("mainstream", "baseline", "navigation")]
        for status in ("passed", "failed"):
            with tempfile.TemporaryDirectory() as dest, \
                    patch("run_ui_device_checks.assert_device", return_value=dict(measured)), \
                    patch("run_ui_device_checks.adb"), \
                    patch("run_ui_device_checks.clear_device_screens"), \
                    patch("run_ui_device_checks.execute_instrumentation", return_value={"status": status, "tests": len(ids), "test_ids": ids}), \
                    patch("run_ui_device_checks.collect_screens", return_value=(["home"], 1)):
                result = run_fresh_suite("mainstream", "baseline", "unused.log", dest, "navigation")
                self.assertEqual(status, result["status"])
                self.assertEqual("incomplete", result["screenshot_observation"]["status"])
                self.assertEqual(["home"], result["missing_screens"])

    def test_stale_screens_must_be_removed(self):
        with patch("run_ui_device_checks.adb", side_effect=["home-android.png", "", "home-android.png"]):
            with self.assertRaises(RuntimeError):
                clear_device_screens()


if __name__ == "__main__":
    unittest.main()
