#!/usr/bin/env python3
"""Measure real frames with animations enabled; restore the deterministic suite's settings."""
import json
import os
import platform
import time
import subprocess
import shlex
from pathlib import Path
from run_ui_device_checks import adb, execute_instrumentation, DEVICE_ROOT, assert_device
from device_matrix import dimensions
import argparse

def environment_snapshot():
    # Read-only, low-overhead snapshots around the measured scene. No trace
    # or profiler is started while the acceptance frames are being sampled.
    paths = ("/proc/stat", "/proc/meminfo", "/proc/pressure/cpu",
             "/proc/pressure/memory", "/proc/pressure/io", "/sys/fs/cgroup/cpu.stat")
    host = {}
    for name in paths:
        path = Path(name)
        if path.is_file():
            host[name] = path.read_text(encoding="utf-8")
    return {"monotonic_ns": time.monotonic_ns(), "host_cpu_count": os.cpu_count(),
            "host_platform": platform.platform(), "host": host,
            "guest_cpus": adb("shell", "getconf", "_NPROCESSORS_ONLN"),
            "guest_meminfo": adb("shell", "cat", "/proc/meminfo"),
            "guest_build": adb("shell", "getprop", "ro.build.fingerprint")}

def main():
    parser = argparse.ArgumentParser()
    target = parser.add_mutually_exclusive_group(required=True)
    target.add_argument("--profile")
    target.add_argument("--physical-serial")
    parser.add_argument("--stream-seconds", type=int)
    parser.add_argument("--stream-rate", type=int)
    args = parser.parse_args()
    physical = args.physical_serial is not None
    if physical:
        # Explicit operator-selected dedicated device. Do not override its display
        # or renderer. A plugged device cannot provide discharge evidence.
        os.environ["ANDROID_SERIAL"] = args.physical_serial
        if adb("get-state") != "device" or adb("shell", "getprop", "ro.product.cpu.abi") != "arm64-v8a" or any(
                adb("shell", "getprop", key) == "1" for key in ("ro.kernel.qemu", "ro.boot.qemu")):
            raise RuntimeError("Physical ARM64 device required")
    else:
        assert_device(dimensions(args.profile, "baseline"), dimensions(args.profile, "baseline"))
    seconds = args.stream_seconds if args.stream_seconds is not None else (1800 if physical else 8)
    rate = args.stream_rate if args.stream_rate is not None else (100 if physical else 25)
    if not 8 <= seconds <= 1800 or not 10 <= rate <= 100:
        raise ValueError("Invalid pressure workload")
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
        if not physical:
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
        before = environment_snapshot()
        try:
            arguments = {"streamSeconds": str(seconds), "streamRate": str(rate),
                         "physicalPerformance": "true" if physical else "false"}
            if not physical:
                arguments["expectedRenderer"] = "Pipeline=Skia (OpenGL)"
            result = execute_instrumentation(["UiMotionPerformanceInstrumentedTest"], "ui-motion.log", arguments)
        finally:
            Path("ui-motion-environment.json").write_text(json.dumps({
                "before": before, "after": environment_snapshot()
            }, indent=2), encoding="utf-8")
        Path("ui-motion-diagnosis.json").write_text(json.dumps(result, indent=2))
        subprocess.run(["adb", "pull", remote, str(local)], check=True)
        data = json.loads(local.read_text())
        if not physical and "Pipeline=Skia (OpenGL)" not in data.get("renderer_pipelines", []):
            raise RuntimeError("The actual app did not use Skia OpenGL")
        if result["status"] != "passed" or result["tests"] != 1 or data["frames"] < 12:
            raise RuntimeError("Actual motion test did not pass")
        resources = data.get("resources", {})
        pressure = data.get("stream_pressure", {})
        if (resources.get("pressure_samples", 0) < 2 or pressure.get("updates", 0) <= 0
                or pressure.get("elapsed_ms", 0) < seconds * 1000):
            raise RuntimeError("Missing actual resource or pressure evidence")
        if physical and (data.get("device_class") != "physical_arm64" or not resources.get("unplugged")
                or (resources.get("consumed_mwh") or 0) <= 0):
            raise RuntimeError("Missing physical discharge evidence")
    finally:
        for suffix in ("txt", "png"):
            subprocess.run(["adb", "pull", DEVICE_ROOT + "/ui-motion-failure." + suffix,
                            "ui-motion-failure." + suffix], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        adb("shell", "am", "force-stop", "io.github.sy220284.codexandroid")
        if not physical:
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
