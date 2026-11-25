#!/usr/bin/env python3
"""Verify an in-app APK replacement followed by the system installer's Open action.

Run on an Android emulator with the debug APK and instrumentation APK installed.
The replacement is triggered by the normal, foreground app process via the
release updater's intent builder, not by adb install -r. Instrumentation only
stages the fixture and finishes before the installer is launched.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE = "io.github.sy220284.codexandroid"
TEST = PACKAGE + ".SelfUpdateInstallFlowInstrumentedTest"
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
INSTALLER_PACKAGES = {"com.android.packageinstaller", "com.google.android.packageinstaller"}
CI_INSTALL_ACTION = PACKAGE + ".ci.SELF_UPDATE"


def adb(*args: str, check: bool = True, input_file=None) -> str:
    process = subprocess.run(
        ["adb", *args], stdin=input_file, capture_output=True, check=False
    )
    output = (process.stdout + process.stderr).decode("utf-8", errors="replace")
    if check and process.returncode:
        raise RuntimeError(f"adb {' '.join(args)} failed ({process.returncode}): {output}")
    return output


def copy_fixture(apk: Path) -> None:
    # Startup cleanup now removes unowned cache/updates content. Stage the
    # test APK outside that cache, and let the instrumentation test explicitly
    # prepare, verify, and hand it to the installer using the production cache.
    adb("shell", "run-as", PACKAGE, "mkdir", "-p", "files")
    with apk.open("rb") as stream:
        # Quote the redirection for the inner run-as shell. Without quoting,
        # adb's outer shell redirects as the shell UID.
        adb(
            "shell", "-T",
            f"run-as {PACKAGE} sh -c 'cat > files/ci-self-update.apk'",
            input_file=stream
        )
    expected = apk.stat().st_size
    actual = int(adb(
        "shell", "run-as", PACKAGE, "stat", "-c", "%s",
        "files/ci-self-update.apk"
    ).strip())
    if actual != expected:
        raise RuntimeError(f"APK fixture truncated: {actual} bytes vs {expected} bytes")
    print(f"APK fixture: {actual} bytes, same signed application package", flush=True)


def stage_fixture() -> None:
    output = adb(
        "shell", "am", "instrument", "-w", "-r",
        "-e", "class", TEST, RUNNER,
    )
    print(output, flush=True)
    if "OK (1 test)" not in output:
        raise RuntimeError("The application failed to stage the verified APK")


def launch_install_ui() -> str:
    # Instrumentation has finished; run the source Activity as a normal app.
    # Its replacement must not depend on AndroidJUnitRunner/ActivityScenario.
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
    pid, _ = wait_until_launched(timeout=25)
    output = adb(
        "shell", "am", "broadcast", "-a", CI_INSTALL_ACTION,
        "-n", PACKAGE + "/.CiSelfUpdateReceiver",
    )
    print(output, flush=True)
    if not re.search(r"Broadcast completed: result=-1(?:\s|,|$)", output):
        raise RuntimeError("Foreground app rejected installer handoff: " + output)
    return pid


def hierarchy() -> ET.Element | None:
    output = adb("shell", "uiautomator", "dump", "/sdcard/self-update-window.xml", check=False)
    if "dumped to:" not in output.lower():
        return None
    xml = adb("shell", "cat", "/sdcard/self-update-window.xml", check=False)
    start = xml.find("<?xml")
    if start < 0:
        return None
    try:
        return ET.fromstring(xml[start:])
    except ET.ParseError:
        return None


def clickable_center(root: ET.Element, texts: set[str]) -> tuple[int, int] | None:
    for node in root.iter("node"):
        labels = {
            node.get("text", "").strip().lower(),
            node.get("content-desc", "").strip().lower(),
        }
        if not (labels & texts):
            continue
        # Only the actual PackageInstaller may supply either confirmation.
        # A matching label in Launcher or in the app is not installer evidence.
        if node.get("package") not in INSTALLER_PACKAGES:
            continue
        if node.get("enabled") == "false":
            continue
        match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
        if match:
            x1, y1, x2, y2 = map(int, match.groups())
            if x2 > x1 and y2 > y1:
                return ((x1 + x2) // 2, (y1 + y2) // 2)
    return None


def click_system_button(labels: set[str], timeout: float) -> None:
    deadline = time.monotonic() + timeout
    last_ui = ""
    while time.monotonic() < deadline:
        root = hierarchy()
        if root is not None:
            last_ui = " ".join(
                value for node in root.iter("node")
                if (value := node.get("text", "").strip())
            )
            coordinate = clickable_center(root, labels)
            if coordinate:
                adb("shell", "input", "tap", str(coordinate[0]), str(coordinate[1]))
                print(f"System installer button {labels} pressed at {coordinate}", flush=True)
                return
        time.sleep(1.5)
    raise RuntimeError(f"System installer button {sorted(labels)} missing; UI: {last_ui[:2000]}")


def parse_last_update_time(package_dump: str) -> str:
    """Read the installed package's update timestamp from Android PackageManager."""
    match = re.search(r"(?m)^\s*lastUpdateTime=(.+)$", package_dump)
    if match is None or not match.group(1).strip():
        raise RuntimeError("PackageManager did not expose lastUpdateTime")
    return match.group(1).strip()


def installed_update_time() -> str:
    return parse_last_update_time(adb("shell", "dumpsys", "package", PACKAGE))


def verify_replacement(before: str, after: str) -> None:
    if before == after:
        raise RuntimeError(
            f"System installer Open was visible, but package lastUpdateTime "
            f"did not change ({before}); APK replacement is unverified"
        )


def capture_screenshot(path: Path) -> None:
    with path.open("wb") as out:
        process = subprocess.run(
            ["adb", "exec-out", "screencap", "-p"],
            stdout=out, stderr=subprocess.PIPE, check=False,
        )
    if process.returncode:
        raise RuntimeError(
            f"Failed to capture screen: {process.stderr.decode('utf-8', errors='replace')}"
        )


def wait_until_launched(timeout: float) -> tuple[str, str]:
    deadline = time.monotonic() + timeout
    last_activity = ""
    while time.monotonic() < deadline:
        activities = adb("shell", "dumpsys", "activity", "activities", check=False)
        resumed = [
            line.strip() for line in activities.splitlines()
            if "topResumedActivity" in line or "mResumedActivity" in line
        ]
        windows = adb("shell", "dumpsys", "window", check=False)
        focused_window = [
            line.strip() for line in windows.splitlines()
            if "mCurrentFocus=" in line
        ]
        last_activity = " | ".join(resumed + focused_window)
        pid = adb("shell", "pidof", PACKAGE, check=False).strip()
        if (pid and any(PACKAGE in line and "MainActivity" in line for line in resumed)
                and any(PACKAGE in line and "MainActivity" in line for line in focused_window)):
            print(f"Foreground MainActivity confirmed (PID {pid})", flush=True)
            return pid, last_activity
        time.sleep(1)
    raise RuntimeError(
        "Install completed, but Open did not launch the application; "
        f"last focused activity: {last_activity}"
    )



def failure_diagnostics() -> None:
    """Preserve evidence of installer handoff failures; never retry or mask them."""
    print("SELF UPDATE FAILURE DIAGNOSTICS", file=sys.stderr, flush=True)
    for title, args in (
        ("top activities", ("shell", "dumpsys", "activity", "activities")),
        ("window focus", ("shell", "dumpsys", "window")),
        ("recent installer events", ("shell", "logcat", "-b", "all", "-d", "-t", "3500",
                                     "-v", "threadtime")),
    ):
        result = adb(*args, check=False)
        lines = result.splitlines()
        if title == "top activities":
            lines = [line for line in lines if any(word in line for word in
                ("ResumedActivity", "topResumedActivity", "mResumedActivity",
                 "packageinstaller", "PackageInstaller", PACKAGE))][-35:]
        elif title == "window focus":
            lines = [line for line in lines if any(word in line for word in
                ("mCurrentFocus", "mFocusedApp", "packageinstaller", "PackageInstaller"))][-20:]
        if title == "recent installer events":
            lines = [line for line in lines if any(word in line for word in
                ("PackageInstaller", "PackageManager", "ActivityTaskManager", "ActivityManager",
                 "AndroidRuntime", "InstallSuccess", "InstallInstalling", PACKAGE))]
        print(title + ":\n" + "\n".join(lines[-90:]), file=sys.stderr, flush=True)
    screenshot = Path("ui-screenshots/self-update-failure-android.png")
    try:
        result = subprocess.run(["adb", "exec-out", "screencap", "-p"],
                                capture_output=True, check=True)
        if result.stdout.startswith(b"\x89PNG"):
            screenshot.parent.mkdir(parents=True, exist_ok=True)
            screenshot.write_bytes(result.stdout)
            print("Failure screenshot: " + str(screenshot), file=sys.stderr, flush=True)
    except (OSError, subprocess.CalledProcessError) as exc:
        print("Screenshot collection failed: " + str(exc), file=sys.stderr, flush=True)

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--report", type=Path, default=Path("self-update-open.json"))
    parser.add_argument("--screenshot", type=Path, default=Path("self-update-after-open.png"))
    parser.add_argument("--commit", default="")
    args = parser.parse_args()
    if not args.apk.is_file():
        parser.error(f"APK not found: {args.apk}")

    report = {
        "schema_version": 1,
        "test_type": "android_emulator_debug_same_apk",
        "commit": args.commit,
        "package": PACKAGE,
        "apk_sha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(),
        "android_api": adb("shell", "getprop", "ro.build.version.sdk").strip(),
        "device_fingerprint": adb("shell", "getprop", "ro.build.fingerprint").strip(),
        "display_size": adb("shell", "wm", "size").strip(),
        "status": "running",
        "installer_buttons": [],
    }
    try:
        # Grant the same app install-source permission a human enables in Settings.
        # Install and Open remain actual system installer button presses.
        adb("shell", "appops", "set", PACKAGE, "REQUEST_INSTALL_PACKAGES", "allow")
        copy_fixture(args.apk)
        before = installed_update_time()
        report["last_update_time_before"] = before
        print(f"PackageManager before installer: lastUpdateTime={before}", flush=True)

        stage_fixture()
        previous_pid = launch_install_ui()
        report["pid_before_install"] = previous_pid

        click_system_button({"install", "update", "安装", "更新"}, timeout=35)
        report["installer_buttons"].append("Install")
        click_system_button({"open", "打开"}, timeout=90)
        report["installer_buttons"].append("Open")

        after = installed_update_time()
        report["last_update_time_after"] = after
        verify_replacement(before, after)
        print(f"PASS: PackageManager lastUpdateTime changed: {before} -> {after}", flush=True)

        pid, resumed = wait_until_launched(timeout=25)
        if previous_pid and previous_pid == pid:
            raise RuntimeError(f"Installer did not replace old application process PID {pid}")
        report["pid_after_open"] = pid
        report["foreground_after_open"] = resumed
        report["status"] = "passed"
        print(f"PASS: installer Open launched new application process {pid}", flush=True)
    except Exception as error:
        report["status"] = "failed"
        report["error"] = str(error)
        # Distinguish an aborted installation from a successful replacement
        # whose system completion window disappeared. Neither counts as passing.
        try:
            observed = installed_update_time()
            report["last_update_time_at_failure"] = observed
            before = report.get("last_update_time_before")
            report["replacement_observed_at_failure"] = bool(before and observed != before)
            print(f"PackageManager at failure: lastUpdateTime={observed}", flush=True)
        except Exception as diagnosis_error:
            report["package_manager_diagnostic_error"] = str(diagnosis_error)
        raise
    finally:
        # Keep a screenshot even on failure, so green CI status is never the only
        # evidence and failures preserve the actual installer/launcher screen.
        try:
            capture_screenshot(args.screenshot)
            report["screenshot"] = str(args.screenshot)
        except Exception as screenshot_error:
            report["screenshot_error"] = str(screenshot_error)
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"Self-update evidence: {args.report} ({report['status']})", flush=True)
    return 0

if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:
        print(f"SELF UPDATE OPEN FAILED: {error}", file=sys.stderr, flush=True)
        failure_diagnostics()
        sys.exit(1)
