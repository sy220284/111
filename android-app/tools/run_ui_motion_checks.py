#!/usr/bin/env python3
"""Measure real frames with animations enabled; restore the deterministic suite's settings."""
import json
import subprocess
import shlex
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
    renderer_property = "debug.hwui.renderer"
    original_renderer = adb("shell", "getprop", renderer_property)
    remote = DEVICE_ROOT + "/ui-motion-performance.json"
    local = Path("ui-motion-performance.json")
    local.unlink(missing_ok=True)
    try:
        for name in settings:
            adb("shell", "settings", "put", "global", name, "1.0")
            if float(adb("shell", "settings", "get", "global", name)) != 1.0:
                raise RuntimeError("System animations not enabled: " + name)
        adb("shell", "rm", "-f", remote)
        package = "io.github.sy220284.codexandroid"
        adb("shell", "am", "force-stop", package)
        # The free runner has no physical GPU. Lavapipe with forced Vulkan
        # repeatedly exceeded the unchanged frame budget even with 4 vCPUs.
        # Use the same SwiftShader GLES route as the functional device suite.
        # Require actual app pipeline evidence and retain all measured frames.
        adb("shell", "setprop", renderer_property, "skiagl")
        if adb("shell", "getprop", renderer_property) != "skiagl":
            raise RuntimeError("Failed to select Skia OpenGL")
        # Measure the same APK after ART compilation, rather than mixing first-run
        # debug JIT compilation with renderer latency. No frames are discarded.
        compiled = adb("shell", "cmd", "package", "compile", "-m", "speed", "-f", package)
        if "Success" not in compiled:
            raise RuntimeError("ART performance preparation failed: " + compiled)
        print("ART compilation: " + compiled)
        print("Host logical CPUs:", __import__("os").cpu_count())
        print("Guest CPUs:", adb("shell", "getconf", "_NPROCESSORS_ONLN"))
        adb("shell", "dumpsys", "gfxinfo", package, "reset")
        result = execute_instrumentation(["UiMotionPerformanceInstrumentedTest"], "ui-motion.log",
                                         {"expectedRenderer": "Pipeline=Skia (OpenGL)"})
        Path("ui-motion-diagnosis.json").write_text(json.dumps(result, indent=2))
        subprocess.run(["adb", "pull", remote, str(local)], check=True)
        data = json.loads(local.read_text())
        if "Pipeline=Skia (OpenGL)" not in data.get("renderer_pipelines", []):
            raise RuntimeError("The actual app did not use Skia OpenGL")
        if result["status"] != "passed" or result["tests"] != 1 or data["frames"] < 12:
            raise RuntimeError("Actual motion test did not pass")
    finally:
        for suffix in ("txt", "png"):
            subprocess.run(["adb", "pull", DEVICE_ROOT + "/ui-motion-failure." + suffix,
                            "ui-motion-failure." + suffix], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        adb("shell", "am", "force-stop", "io.github.sy220284.codexandroid")
        adb("shell", "setprop " + renderer_property + " " + shlex.quote(original_renderer))
        if adb("shell", "getprop", renderer_property) != original_renderer:
            raise RuntimeError("Failed to restore original HWUI renderer")
        for name, value in original.items():
            if value == "null":
                adb("shell", "settings", "delete", "global", name)
            else:
                adb("shell", "settings", "put", "global", name, value)

if __name__ == "__main__":
    main()
