#!/usr/bin/env python3
"""Verify an in-app APK replacement followed by the system installer's Open action.

Run on an Android emulator with the debug APK and instrumentation APK installed.
The replacement is triggered from MainActivity via the same intent builder
used by the release updater, not by adb install -r.
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE = "io.github.sy220284.codexandroid"
TEST = PACKAGE + ".SelfUpdateInstallFlowInstrumentedTest"
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"


def adb(*args: str, check: bool = True, input_file=None) -> str:
    process = subprocess.run(
        ["adb", *args], stdin=input_file, capture_output=True, check=False
    )
    output = (process.stdout + process.stderr).decode("utf-8", errors="replace")
    if check and process.returncode:
        raise RuntimeError(f"adb {' '.join(args)} failed ({process.returncode}): {output}")
    return output


def copy_fixture(apk: Path) -> None:
    adb("shell", "run-as", PACKAGE, "mkdir", "-p", "cache/updates")
    with apk.open("rb") as stream:
        # Quote the redirection for the inner run-as shell. Without quoting,
        # adb's outer shell redirects as the shell UID and cannot write the
        # application's private cache.
        adb(
            "shell", "-T",
            f"run-as {PACKAGE} sh -c 'cat > cache/updates/ci-self-update.apk'",
            input_file=stream
        )
    expected = apk.stat().st_size
    actual = int(adb(
        "shell", "run-as", PACKAGE, "stat", "-c", "%s",
        "cache/updates/ci-self-update.apk"
    ).strip())
    if actual != expected:
        raise RuntimeError(f"APK fixture truncated: {actual} bytes vs {expected} bytes")
    print(f"APK fixture: {actual} bytes, same signed application package", flush=True)


def launch_install_ui() -> None:
    output = adb(
        "shell", "am", "instrument", "-w", "-r",
        "-e", "class", TEST, RUNNER,
    )
    print(output, flush=True)
    if "OK (1 test)" not in output:
        raise RuntimeError("The application failed to open the system installer")


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
        if node.get("enabled") == "false":
            continue
        match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
        if match:
            x1, y1, x2, y2 = map(int, match.groups())
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


def wait_until_launched(timeout: float) -> None:
    deadline = time.monotonic() + timeout
    last_activity = ""
    while time.monotonic() < deadline:
        activities = adb("shell", "dumpsys", "activity", "activities", check=False)
        focused = [
            line.strip() for line in activities.splitlines()
            if "topResumedActivity" in line or "mResumedActivity" in line
        ]
        windows = adb("shell", "dumpsys", "window", check=False)
        focused += [
            line.strip() for line in windows.splitlines()
            if "mCurrentFocus=" in line or "mFocusedApp=" in line
        ]
        last_activity = " | ".join(focused)
        pid = adb("shell", "pidof", PACKAGE, check=False).strip()
        if pid and PACKAGE in last_activity and "MainActivity" in last_activity:
            print(f"PASS: system Open started {PACKAGE}/.MainActivity (PID {pid})", flush=True)
            return
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
        ("recent installer events", ("shell", "logcat", "-d", "-t", "220",
                                     "-s", "ActivityTaskManager:I", "PackageInstaller:I", "*:S")),
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
        print(title + ":\n" + "\n".join(lines[-90:]), file=sys.stderr, flush=True)
    screenshot = Path("ui-screenshots/self-update-failure-android35.png")
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
    args = parser.parse_args()
    if not args.apk.is_file():
        parser.error(f"APK not found: {args.apk}")
    # The app is the installation source. Authorize it as the CI device owner
    # would do via Settings > Install unknown apps; retain the actual system
    # installer confirmation and Open buttons for the end-to-end test.
    adb("shell", "appops", "set", PACKAGE, "REQUEST_INSTALL_PACKAGES", "allow")
    copy_fixture(args.apk)
    launch_install_ui()
    click_system_button({"install", "update", "安装", "更新"}, timeout=35)
    click_system_button({"open", "打开"}, timeout=90)
    wait_until_launched(timeout=25)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:
        print(f"SELF UPDATE OPEN FAILED: {error}", file=sys.stderr, flush=True)
        failure_diagnostics()
        sys.exit(1)
