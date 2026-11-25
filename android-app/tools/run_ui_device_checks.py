#!/usr/bin/env python3
"""Execute the real Android test suite, retain evidence and classify failures."""
import argparse
import json
import re
import subprocess
import os
import time
import uuid
from collect_ui_screenshots import parse_discovered_screens
from pathlib import Path

PACKAGE = "io.github.sy220284.codexandroid"
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
SUITE = ["UiNavigationInstrumentedTest", "UiPopulatedSubpagesInstrumentedTest",
         "CodexInteractionComponentsInstrumentedTest", "CodexOverlayInstrumentedTest",
         "EngineSettingInstrumentedTest", "SelfUpdateInstallerIntentInstrumentedTest"]
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


def clear_device_screens():
    command = f'if [ -d "{DEVICE_ROOT}" ]; then ls -1 "{DEVICE_ROOT}"; fi'
    for screen in parse_discovered_screens(adb("shell", command)):
        adb("shell", "rm", "-f", DEVICE_ROOT + "/" + screen + "-android35.png")
    if parse_discovered_screens(adb("shell", command)):
        raise RuntimeError("Previous device screenshots were not cleared")


def assert_device(width, height, font=None):
    size = adb("shell", "wm", "size")
    density = adb("shell", "wm", "density")
    actual_size = re.findall(r"(?:Override|Physical) size: (\d+x\d+)", size)
    actual_density = re.findall(r"(?:Override|Physical) density: (\d+)", density)
    if not actual_size or actual_size[-1] != f"{width}x{height}" or not actual_density or actual_density[-1] != "160":
        raise RuntimeError("Device configuration does not match evidence label")
    actual_font = adb("shell", "settings", "get", "system", "font_scale")
    measured_font = float(actual_font) if actual_font != "null" else 1.0
    if font is not None and measured_font != float(font):
        raise RuntimeError("Device font scale does not match evidence label")
    return measured_font


def run_fresh_suite(width, height, font, log, destination):
    font = assert_device(width, height, font)
    adb("shell", "am", "force-stop", PACKAGE)
    clear_device_screens()
    run_id = uuid.uuid4().hex
    started = time.time()
    result = execute_instrumentation(SUITE, log)
    missing, code = collect_screens(destination)
    manifest = Path(destination) / "manifest.json"
    if manifest.is_file():
        evidence = json.loads(manifest.read_text(encoding="utf-8"))
        evidence.update(run_id=run_id, started_at_epoch=started,
                        commit=os.environ.get("GITHUB_SHA"), width_dp=width,
                        height_dp=height, font_scale=font)
        manifest.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    result.update(run_id=run_id, width_dp=width, height_dp=height,
                  font_scale=font, missing_screens=missing, screenshot_collection_exit=code)
    if missing or code:
        result.update(status="failed", evidence_category="screenshot_missing_or_collection_error")
    return result


def collect_screens(destination):
    collected = subprocess.run(["python3", "android-app/tools/collect_ui_screenshots.py",
                                "--destination", destination], check=False)
    manifest = Path(destination) / "manifest.json"
    missing = json.loads(manifest.read_text(encoding="utf-8"))["missing"] if manifest.is_file() else ["screenshot_manifest"]
    return missing, collected.returncode


def baseline(width):
    return run_fresh_suite(width, 800, 1.0, "ui-instrument-result.log", "ui-screenshots")


def adaptive(width):
    height, font = VARIANTS[width]
    original_font = adb("shell", "settings", "get", "system", "font_scale")
    original_size = re.search(r"Override size: (\d+x\d+)", adb("shell", "wm", "size"))
    try:
        adb("shell", "wm", "size", f"{width}x{height}")
        adb("shell", "settings", "put", "system", "font_scale", font)
        result = run_fresh_suite(width, height, float(font), "ui-adaptive-result.log", "ui-screenshots-adaptive")
        baseline_result = json.loads(Path("ui-baseline-diagnosis.json").read_text(encoding="utf-8"))
        result["expected_tests"] = baseline_result["tests"]
        if result["tests"] != baseline_result["tests"]:
            result.update(status="failed", evidence_category="adaptive_test_count_mismatch")
        return result
    finally:
        adb("shell", "wm", "size", original_size.group(1) if original_size else "reset")
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
