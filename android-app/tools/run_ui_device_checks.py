#!/usr/bin/env python3
"""Execute the real Android test suite, retain evidence and classify failures."""
import argparse
import json
import re
import subprocess
from pathlib import Path

PACKAGE = "io.github.sy220284.codexandroid"
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
SUITE = ["UiNavigationInstrumentedTest", "UiPopulatedSubpagesInstrumentedTest",
         "CodexInteractionComponentsInstrumentedTest", "CodexOverlayInstrumentedTest",
         "EngineSettingInstrumentedTest", "SelfUpdateInstallerIntentInstrumentedTest"]
ADAPTIVE_TESTS = [
    "UiNavigationInstrumentedTest#chatKeyboardKeepsComposerActionsAccessible",
    "UiNavigationInstrumentedTest#narrowConversationToolbarKeepsPrimaryActionsVisible",
    "CodexInteractionComponentsInstrumentedTest#attachmentPreviewSupportsZoomSwitchResetAndRemove",
]
ADAPTIVE_SCREENS = ["chat-keyboard-open", "chat-toolbar", "attachment-sheet", "audit-attachment-preview"]
VARIANTS = {360: (720, "1.3"), 393: (720, "1.3"), 411: (915, "1.5")}
DEVICE_ROOT = "/sdcard/Android/data/" + PACKAGE + "/files"


def category(error):
    if "CalledFromWrongThreadException" in error:
        return "ui_thread"
    if "ComposeTimeoutException" in error or "TimeoutException" in error:
        return "state_or_layout_timeout"
    if "AssertionError" in error:
        return "interaction_assertion"
    return "test_exception"


def parse_instrumentation(log, exit_code):
    ok = re.findall(r"(?m)^OK \((\d+) tests?\)\s*$", log)
    failure_counts = re.findall(r"Tests run:\s*(\d+),\s*Failures:\s*(\d+)", log)
    failures = [
        {"test": match.group(1), "error": match.group(2), "category": category(match.group(2))}
        for match in re.finditer(r"(?m)^\d+\)\s+([^\r\n]+)\r?\n([^\r\n]+)", log)
    ]
    crashed = bool(re.search(r"(?m)^INSTRUMENTATION_(?:FAILED|ABORTED)|^INSTRUMENTATION_RESULT: shortMsg=", log))
    count = int(ok[-1]) if ok else int(failure_counts[-1][0]) if failure_counts else 0
    # am instrument may return 0 even when JUnit failed or aborted.
    passed = exit_code == 0 and len(ok) == 1 and count > 0 and not failure_counts and not failures and not crashed
    result = {"status": "passed" if passed else "failed",
              "tests": count, "exit_code": exit_code, "failures": failures}
    if not passed and not failures:
        result["category"] = "runner_or_device" if crashed or not count or exit_code else "test_failure"
    return result


def adb(*args):
    return subprocess.check_output(["adb", *args], text=True).strip()


def execute_instrumentation(classes, log_path):
    command = ["adb", "shell", "am", "instrument", "-w", "-r", "-e", "class",
               ",".join(PACKAGE + "." + c for c in classes), RUNNER]
    with Path(log_path).open("w", encoding="utf-8") as evidence:
        with subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              text=True, errors="replace") as process:
            for line in process.stdout:
                evidence.write(line)
                print(line, end="", flush=True)
            code = process.wait()
    return parse_instrumentation(Path(log_path).read_text(encoding="utf-8"), code)


def pull_adaptive_screens():
    destination = Path("ui-screenshots-adaptive")
    destination.mkdir(exist_ok=True)
    missing = []
    for screen in ADAPTIVE_SCREENS:
        name = screen + "-android35.png"
        local = destination / name
        pulled = subprocess.run(["adb", "pull", DEVICE_ROOT + "/" + name, str(local)], check=False)
        if pulled.returncode or not local.is_file() or local.stat().st_size < 8:
            missing.append(screen)
        else:
            with local.open("rb") as image:
                if image.read(8) != b"\x89PNG\r\n\x1a\n":
                    missing.append(screen)
    return missing


def baseline(width):
    result = execute_instrumentation(SUITE, "ui-instrument-result.log")
    collected = subprocess.run(["python3", "android-app/tools/collect_ui_screenshots.py"], check=False)
    manifest = Path("ui-screenshots/manifest.json")
    if manifest.is_file():
        screens = json.loads(manifest.read_text(encoding="utf-8"))
        missing = screens["missing"]
    else:
        missing = ["screenshot_manifest"]
    result.update(width_dp=width, height_dp=800, missing_screens=missing,
                  screenshot_collection_exit=collected.returncode)
    if missing or collected.returncode:
        result["status"] = "failed"
        result["evidence_category"] = "screenshot_missing_or_collection_error"
    return result


def adaptive(width):
    height, font = VARIANTS[width]
    original_font = adb("shell", "settings", "get", "system", "font_scale")
    try:
        adb("shell", "wm", "size", f"{width}x{height}")
        adb("shell", "settings", "put", "system", "font_scale", font)
        # Capture only new evidence from this configuration, never baseline files.
        for screen in ADAPTIVE_SCREENS:
            adb("shell", "rm", "-f", DEVICE_ROOT + "/" + screen + "-android35.png")
        actual_size = adb("shell", "wm", "size")
        actual_font = adb("shell", "settings", "get", "system", "font_scale")
        if f"Override size: {width}x{height}" not in actual_size or float(actual_font) != float(font):
            raise RuntimeError("Adaptive device configuration not applied")
        adb("shell", "am", "force-stop", PACKAGE)
        result = execute_instrumentation(ADAPTIVE_TESTS, "ui-adaptive-result.log")
        missing = pull_adaptive_screens()
        result.update(width_dp=width, height_dp=height, font_scale=float(font), missing_screens=missing)
        if result["tests"] != len(ADAPTIVE_TESTS) or missing:
            result["status"] = "failed"
            result["evidence_category"] = "adaptive_test_count_or_screenshot_missing"
        return result
    finally:
        adb("shell", "wm", "size", f"{width}x800")
        if original_font == "null":
            adb("shell", "settings", "delete", "system", "font_scale")
        else:
            adb("shell", "settings", "put", "system", "font_scale", original_font)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--width", type=int, choices=sorted(VARIANTS), required=True)
    parser.add_argument("--mode", choices=["baseline", "adaptive"], required=True)
    args = parser.parse_args()
    report = Path("ui-" + args.mode + "-diagnosis.json")
    try:
        result = baseline(args.width) if args.mode == "baseline" else adaptive(args.width)
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        result = {"status": "failed", "category": "runner_or_device", "error": str(error), "width_dp": args.width}
    report.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2), flush=True)
    return int(result["status"] != "passed")


if __name__ == "__main__":
    raise SystemExit(main())
