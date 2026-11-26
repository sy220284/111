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
from device_matrix import CONFIG, PROFILES, dimensions, INDEPENDENT_CLASS, independent_device, minimum_tests

PACKAGE = "io.github.sy220284.codexandroid"
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
SUITE = ["UiNavigationInstrumentedTest", "UiPopulatedSubpagesInstrumentedTest",
         "CodexInteractionComponentsInstrumentedTest", "CodexOverlayInstrumentedTest",
         "EngineSettingInstrumentedTest"]

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


def completed_test_ids(log):
    """Read the runner's successful completion records, not start messages."""
    current = {}
    tests = []
    for line in log.splitlines():
        match = re.fullmatch(r"INSTRUMENTATION_STATUS: (class|test)=(.+)", line.strip())
        if match:
            current[match[1]] = match[2]
        elif line.strip().startswith("INSTRUMENTATION_STATUS_CODE:"):
            if line.strip() == "INSTRUMENTATION_STATUS_CODE: 0":
                if set(current) != {"class", "test"}:
                    raise ValueError("Incomplete JUnit completion record")
                tests.append(current["class"]+"#"+current["test"])
            current = {}
    return sorted(tests)

def adb(*args):
    return subprocess.check_output(["adb", *args], text=True).strip()


def execute_instrumentation(classes, log_path, arguments=None):
    command = ["adb", "shell", "am", "instrument", "-w", "-r", "-e", "class",
               ",".join(PACKAGE + "." + c for c in classes)]
    for key, value in (arguments or {}).items():
        command.extend(["-e", key, str(value)])
    command.append(RUNNER)
    with Path(log_path).open("w", encoding="utf-8") as evidence:
        with subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              text=True, errors="replace") as process:
            for line in process.stdout:
                evidence.write(line)
                print(line, end="", flush=True)
            code = process.wait()
    log = Path(log_path).read_text(encoding="utf-8")
    result = parse_instrumentation(log, code)
    result["test_ids"] = completed_test_ids(log)
    if result["status"] == "passed" and (len(result["test_ids"]) != result["tests"] or len(set(result["test_ids"])) != result["tests"]):
        result.update(status="failed", evidence_category="missing_or_duplicate_test_ids")
    return result


def clear_device_screens():
    command = f'if [ -d "{DEVICE_ROOT}" ]; then ls -1 "{DEVICE_ROOT}"; fi'
    for screen in parse_discovered_screens(adb("shell", command)):
        adb("shell", "rm", "-f", DEVICE_ROOT + "/" + screen + "-android.png")
    if parse_discovered_screens(adb("shell", command)):
        raise RuntimeError("Previous device screenshots were not cleared")


def assert_device(spec, physical_spec):
    size = adb("shell", "wm", "size")
    density = adb("shell", "wm", "density")
    actual_size = re.findall(r"(?:Override|Physical) size: (\d+x\d+)", size)
    actual_density = re.findall(r"(?:Override|Physical) density: (\d+)", density)
    physical_size = re.search(r"Physical size: (\d+x\d+)", size)
    physical_density = re.search(r"Physical density: (\d+)", density)
    expected_physical = f"{physical_spec['width_px']}x{physical_spec['height_px']}"
    if not physical_size or physical_size[1] != expected_physical or not physical_density or int(physical_density[1]) != physical_spec["density_dpi"]:
        raise RuntimeError("Physical AVD LCD resolution/density does not match baseline profile")
    api = int(adb("shell", "getprop", "ro.build.version.sdk"))
    release = adb("shell", "getprop", "ro.build.version.release")
    page_size = int(adb("shell", "getconf", "PAGESIZE"))
    build_fingerprint = adb("shell", "getprop", "ro.build.fingerprint")
    if not build_fingerprint:
        raise RuntimeError("Missing Android build fingerprint")
    width, height, dpi = (spec[k] for k in ("width_px", "height_px", "density_dpi"))
    if api != CONFIG["api"] or release.split(".")[0] != str(CONFIG["android_release"]) or page_size != CONFIG["page_size"]:
        raise RuntimeError("Android OS or memory page size does not match device matrix")
    if not actual_size or actual_size[-1] != f"{width}x{height}" or not actual_density or int(actual_density[-1]) != dpi:
        raise RuntimeError("Device resolution/density does not match evidence label")
    actual_font = adb("shell", "settings", "get", "system", "font_scale")
    measured_font = float(actual_font) if actual_font != "null" else 1.0
    if measured_font != float(spec["font_scale"]):
        raise RuntimeError("Device font scale does not match evidence label")
    return dict(spec, android_api=api, android_release=release, page_size=page_size, build_fingerprint=build_fingerprint,
                physical_size=physical_size[1], physical_density_dpi=int(physical_density[1]),
                width_dp=round(width * 160 / dpi, 2), height_dp=round(height * 160 / dpi, 2))


def run_fresh_suite(profile, mode, log, destination):
    measured = assert_device(dimensions(profile, mode), dimensions(profile, 'baseline'))
    measured.update(profile=profile, mode=mode)
    adb("shell", "am", "force-stop", PACKAGE)
    clear_device_screens()
    run_id = uuid.uuid4().hex
    started = time.time()
    classes=SUITE+[INDEPENDENT_CLASS] if independent_device(profile,mode) else SUITE
    result = execute_instrumentation(classes, log, arguments={
        "expectedWidthPx": measured["width_px"], "expectedDensityDpi": measured["density_dpi"]})
    floor=minimum_tests(profile,mode)
    if result['tests'] < floor:
        result.update(status='failed', evidence_category='ui_test_count_below_floor', minimum_ui_tests=floor)
    missing, code = collect_screens(destination)
    manifest = Path(destination) / "manifest.json"
    if manifest.is_file():
        evidence = json.loads(manifest.read_text(encoding="utf-8"))
        evidence.update(run_id=run_id, started_at_epoch=started,
                        commit=os.environ.get("TEST_SOURCE_SHA") or os.environ.get("GITHUB_SHA"), **measured)
        manifest.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    result.update(run_id=run_id, commit=os.environ.get("TEST_SOURCE_SHA") or os.environ.get("GITHUB_SHA"), **measured, missing_screens=missing, screenshot_collection_exit=code)
    if missing or code:
        result.update(status="failed", evidence_category="screenshot_missing_or_collection_error")
    return result


def collect_screens(destination):
    collected = subprocess.run(["python3", "android-app/tools/collect_ui_screenshots.py",
                                "--destination", destination], check=False)
    manifest = Path(destination) / "manifest.json"
    missing = json.loads(manifest.read_text(encoding="utf-8"))["missing"] if manifest.is_file() else ["screenshot_manifest"]
    return missing, collected.returncode


def prepare_display_change():
    """Configure a disposable AVD with the tested app actually foregrounded."""
    launch = adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
    if not re.search(r"(?m)^Status: ok\s*$", launch):
        raise RuntimeError("App could not own the display before reconfiguration: " + launch)
    deadline = time.monotonic() + 15
    while True:
        activity = adb("shell", "dumpsys", "activity", "activities")
        resumed = [line for line in activity.splitlines()
                   if "mResumedActivity" in line or "topResumedActivity" in line]
        if any(PACKAGE + "/" in line for line in resumed):
            print("DISPLAY_PREPARATION " + json.dumps({"foreground": PACKAGE}), flush=True)
            return
        if time.monotonic() >= deadline:
            raise RuntimeError("App did not become the resumed activity: " + "\n".join(resumed))
        time.sleep(.25)


def baseline(profile):
    return run_fresh_suite(profile, "baseline", "ui-instrument-result.log", "ui-screenshots")


def adaptive(profile):
    # Each configuration owns a disposable AVD. Apply its display override
    # once; do not stop HOME or resize after capturing the test evidence.
    spec = dimensions(profile, "adaptive")
    prepare_display_change()
    adb("shell", "wm", "size", f"{spec['width_px']}x{spec['height_px']}")
    adb("shell", "wm", "density", str(spec["density_dpi"]))
    adb("shell", "settings", "put", "system", "font_scale", str(spec["font_scale"]))
    return run_fresh_suite(profile, "adaptive", "ui-adaptive-result.log", "ui-screenshots-adaptive")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", choices=sorted(PROFILES), required=True)
    parser.add_argument("--mode", choices=["baseline", "adaptive"], required=True)
    args = parser.parse_args()
    report = Path("ui-" + args.mode + "-diagnosis.json")
    try:
        result = baseline(args.profile) if args.mode == "baseline" else adaptive(args.profile)
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        result = {"status": "failed", "category": "runner_or_device", "error": str(error), "profile": args.profile}
    report.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2), flush=True)
    return int(result["status"] != "passed")


if __name__ == "__main__":
    raise SystemExit(main())

