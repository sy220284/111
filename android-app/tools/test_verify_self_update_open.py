"""Contract checks for the Android 35 package-replacement evidence gate."""
import unittest
import xml.etree.ElementTree as ET
from unittest.mock import patch

import verify_self_update_open as check


class SelfUpdateEvidenceTests(unittest.TestCase):
    def test_installer_button_rejects_launcher_label_and_empty_bounds(self):
        root = ET.fromstring("""<hierarchy>
          <node text="Open" package="com.android.launcher3" enabled="true" bounds="[0,0][40,40]"/>
          <node text="Open" package="com.android.packageinstaller" enabled="true" bounds="[0,0][0,0]"/>
        </hierarchy>""")
        self.assertIsNone(check.clickable_center(root, {"open"}))

    def test_installer_button_accepts_real_system_confirmation(self):
        root = ET.fromstring("""<hierarchy>
          <node text="Open" package="com.android.packageinstaller" enabled="true" bounds="[10,20][30,40]"/>
        </hierarchy>""")
        self.assertEqual((20, 30), check.clickable_center(root, {"open"}))

    def test_normal_process_handoff_waits_for_foreground_and_requires_broadcast_result(self):
        calls = []
        def fake_adb(*args, **kwargs):
            calls.append(args)
            if args[1:3] == ("am", "broadcast"):
                return "Broadcast completed: result=-1"
            return ""
        with (patch.object(check, "adb", side_effect=fake_adb),
              patch.object(check, "wait_until_launched", return_value=("123", "foreground")) as wait):
            self.assertEqual("123", check.launch_install_ui())
        wait.assert_called_once_with(timeout=25)
        self.assertEqual(("shell", "am", "force-stop", check.PACKAGE), calls[0])
        self.assertIn("broadcast", calls[-1])

    def test_failed_broadcast_cannot_be_accepted(self):
        with (patch.object(check, "adb", return_value="Broadcast completed: result=0"),
              patch.object(check, "wait_until_launched", return_value=("123", "foreground"))):
            with self.assertRaisesRegex(RuntimeError, "rejected installer handoff"):
                check.launch_install_ui()

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
