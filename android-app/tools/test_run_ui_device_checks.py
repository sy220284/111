#!/usr/bin/env python3
import unittest
from unittest.mock import patch
from run_ui_device_checks import parse_instrumentation, assert_device, clear_device_screens


class DeviceEvidenceTest(unittest.TestCase):
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

    def test_actual_device_parameters_are_required(self):
        with patch("run_ui_device_checks.adb", side_effect=["Physical size: 1080x1920\nOverride size: 393x800", "Physical density: 160", "1.0"]):
            self.assertEqual(1.0, assert_device(393, 800, 1.0))
        with patch("run_ui_device_checks.adb", side_effect=["Physical size: 360x800", "Physical density: 160"]):
            with self.assertRaises(RuntimeError):
                assert_device(393, 800)

    def test_stale_screens_must_be_removed(self):
        with patch("run_ui_device_checks.adb", side_effect=["home-android35.png", "", "home-android35.png"]):
            with self.assertRaises(RuntimeError):
                clear_device_screens()


if __name__ == "__main__":
    unittest.main()
