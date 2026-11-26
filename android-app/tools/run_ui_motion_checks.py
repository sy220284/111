#!/usr/bin/env python3
"""Measure real frames with animations enabled; restore the deterministic suite's settings."""
import json
import subprocess
from pathlib import Path
from run_ui_device_checks import adb, execute_instrumentation, DEVICE_ROOT, assert_device
from device_matrix import dimensions
import argparse

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--profile", required=True)
    args = parser.parse_args()
    assert_device(dimensions(args.profile, "baseline"), dimensions(args.profile, "baseline"))
    settings = ("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
    original = {name: adb("shell", "settings", "get", "global", name) for name in settings}
    remote = DEVICE_ROOT + "/ui-motion-performance.json"
    local = Path("ui-motion-performance.json")
    local.unlink(missing_ok=True)
    try:
        for name in settings:
            adb("shell", "settings", "put", "global", name, "1.0")
            if float(adb("shell", "settings", "get", "global", name)) != 1.0:
                raise RuntimeError("System animations not enabled: " + name)
        adb("shell", "rm", "-f", remote)
        adb("shell", "am", "force-stop", "io.github.sy220284.codexandroid")
        result = execute_instrumentation(["UiMotionPerformanceInstrumentedTest"], "ui-motion.log")
        Path("ui-motion-diagnosis.json").write_text(json.dumps(result, indent=2))
        subprocess.run(["adb", "pull", remote, str(local)], check=True)
        data = json.loads(local.read_text())
        if result["status"] != "passed" or result["tests"] != 1 or data["frames"] < 12:
            raise RuntimeError("Actual motion test did not pass")
    finally:
        for name, value in original.items():
            if value == "null":
                adb("shell", "settings", "delete", "global", name)
            else:
                adb("shell", "settings", "put", "global", name, value)

if __name__ == "__main__":
    main()
