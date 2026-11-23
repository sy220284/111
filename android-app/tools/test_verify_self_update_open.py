"""Contract checks for the Android 35 package-replacement evidence gate."""
import unittest
from unittest.mock import patch

import verify_self_update_open as check


class SelfUpdateEvidenceTests(unittest.TestCase):
    def test_reads_real_package_manager_update_timestamp(self):
        package = """
        Package [io.github.sy220284.codexandroid] (123):
          versionCode=104
          firstInstallTime=2026-11-22 14:01:00
          lastUpdateTime=2026-11-22 14:32:26
        """
        self.assertEqual(
            "2026-11-22 14:32:26", check.parse_last_update_time(package)
        )

    def test_missing_timestamp_fails_closed(self):
        with self.assertRaisesRegex(RuntimeError, "lastUpdateTime"):
            check.parse_last_update_time("Package [unavailable]")

    def test_installer_open_without_package_replacement_fails(self):
        with self.assertRaisesRegex(RuntimeError, "did not change"):
            check.verify_replacement(
                "2026-11-22 14:01:00", "2026-11-22 14:01:00"
            )

    def test_real_package_replacement_is_accepted(self):
        check.verify_replacement(
            "2026-11-22 14:01:00", "2026-11-22 14:32:26"
        )

    def test_resumed_main_activity_and_pid_produce_proof(self):
        def fake_adb(*args, **_kwargs):
            if args[1:3] == ("dumpsys", "activity"):
                return ("topResumedActivity=ActivityRecord{1 "
                        "io.github.sy220284.codexandroid/.MainActivity t4}")
            if args[1:3] == ("dumpsys", "window"):
                return "mCurrentFocus=Window{2 io.github.sy220284.codexandroid/.MainActivity}"
            if args[1] == "pidof":
                return "3203"
            self.fail(f"Unexpected adb request: {args}")
        with patch.object(check, "adb", side_effect=fake_adb):
            pid, foreground = check.wait_until_launched(2)
        self.assertEqual("3203", pid)
        self.assertIn("topResumedActivity", foreground)

    def test_window_history_alone_does_not_claim_foreground(self):
        def fake_adb(*args, **_kwargs):
            if args[1:3] == ("dumpsys", "activity"):
                return "topResumedActivity=ActivityRecord{1 com.android.launcher/.Launcher}"
            if args[1:3] == ("dumpsys", "window"):
                return "mFocusedApp=ActivityRecord{2 io.github.sy220284.codexandroid/.MainActivity}"
            if args[1] == "pidof":
                return "3203"
            self.fail(f"Unexpected adb request: {args}")
        with (patch.object(check, "adb", side_effect=fake_adb),
              patch.object(check.time, "monotonic", side_effect=[0, 0.1, 2]),
              patch.object(check.time, "sleep")):
            with self.assertRaisesRegex(RuntimeError, "did not launch"):
                check.wait_until_launched(1)


if __name__ == "__main__":
    unittest.main()
