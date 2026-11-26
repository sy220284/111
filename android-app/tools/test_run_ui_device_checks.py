#!/usr/bin/env python3
import unittest
import shlex
from unittest.mock import patch
from run_ui_device_checks import SUITE, parse_instrumentation, assert_device, clear_device_screens, completed_test_ids, prepare_display_change, adaptive, instrumentation_command, PACKAGE, RUNNER


class DeviceEvidenceTest(unittest.TestCase):
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

    def test_real_video_decoder_is_in_the_executed_device_suite(self):
        self.assertIn("VideoAttachmentPreparationInstrumentedTest", SUITE)

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
        with patch("run_ui_device_checks.prepare_display_change") as prepare, patch("run_ui_device_checks.adb") as adb, patch("run_ui_device_checks.run_fresh_suite", return_value={"status": "passed"}) as suite:
            self.assertEqual({"status": "passed"}, adaptive("compact"))
            prepare.assert_called_once()
            self.assertEqual(3, adb.call_count)
            suite.assert_called_once_with("compact", "adaptive", "ui-adaptive-result.log", "ui-screenshots-adaptive")

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
        spec = dimensions("compact", "baseline")
        replies = ["Physical size: 1080x2340", "Physical density: 440", str(CONFIG["api"]), str(CONFIG["android_release"]), str(CONFIG["page_size"]), "test/android17/fingerprint", "1.0"]
        with patch("run_ui_device_checks.adb", side_effect=replies):
            evidence = assert_device(spec, spec)
            self.assertEqual(392.73, evidence["width_dp"])
            self.assertEqual(CONFIG["api"], evidence["android_api"])
        for index, bad in [(0, "Physical size: 360x800\nOverride size: 1080x2340"), (1, "Physical density: 160\nOverride density: 440"), (2, "35"), (3, "15"), (4, "4096"), (5, ""), (6, "1.5")]:
            wrong = replies.copy()
            wrong[index] = bad
            with patch("run_ui_device_checks.adb", side_effect=wrong), self.assertRaises(RuntimeError):
                assert_device(spec, spec)

    def test_stale_screens_must_be_removed(self):
        with patch("run_ui_device_checks.adb", side_effect=["home-android.png", "", "home-android.png"]):
            with self.assertRaises(RuntimeError):
                clear_device_screens()


if __name__ == "__main__":
    unittest.main()
