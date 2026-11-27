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
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--profile", help="CI software-rendered AVD profile")
    mode.add_argument("--physical-device", action="store_true", help="Dedicated physical test device with the same APK/test APK installed")
    args = parser.parse_args()
    if args.physical_device:
        # Fail closed on emulators; record actual renderer and device rather
        # than changing the physical device to the CI software pipeline.
        if any(adb("shell", "getprop", prop).strip() == "1" for prop in ("ro.kernel.qemu", "ro.boot.qemu")):
            raise RuntimeError("Physical motion evidence requires a physical device")
        surface = adb("shell", "dumpsys", "SurfaceFlinger")
        renderer = [line.strip() for line in surface.splitlines() if line.strip().startswith("GLES:")]
        if not renderer or any(name in " ".join(renderer).lower() for name in ("swiftshader", "llvmpipe", "lavapipe", "software rasterizer", "angle (google")):
            raise RuntimeError("Physical GPU renderer evidence missing or software-rendered: " + repr(renderer))
        Path("ui-motion-physical-device.json").write_text(json.dumps({
            "model": adb("shell", "getprop", "ro.product.model"),
            "build": adb("shell", "getprop", "ro.build.fingerprint"),
            "renderer": renderer,
            "display": adb("shell", "dumpsys", "display"),
            "thermal_before": adb("shell", "dumpsys", "thermalservice"),
            "source_sha": os.environ.get("TEST_SOURCE_SHA") or os.environ.get("GITHUB_SHA"),
        }, ensure_ascii=False, indent=2), encoding="utf-8")
    else:
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
        if not args.physical_device:
            adb("shell", "setprop", renderer_property, "skiagl")
            if adb("shell", "getprop", renderer_property) != "skiagl":
                raise RuntimeError("Failed to select Skia OpenGL")
        renderer_args = {} if args.physical_device else {"expectedRenderer": "Pipeline=Skia (OpenGL)"}
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
            result = execute_instrumentation(["UiMotionPerformanceInstrumentedTest"], "ui-motion.log",
                                             renderer_args)
        finally:
            Path("ui-motion-environment.json").write_text(json.dumps({
                "before": before, "after": environment_snapshot()
            }, indent=2), encoding="utf-8")
        Path("ui-motion-diagnosis.json").write_text(json.dumps(result, indent=2))
        subprocess.run(["adb", "pull", remote, str(local)], check=True)
        data = json.loads(local.read_text())
        if not args.physical_device and "Pipeline=Skia (OpenGL)" not in data.get("renderer_pipelines", []):
            raise RuntimeError("The actual app did not use Skia OpenGL")
        if args.physical_device:
            evidence_file = Path("ui-motion-physical-device.json")
            evidence = json.loads(evidence_file.read_text(encoding="utf-8"))
            evidence.update(thermal_after=adb("shell", "dumpsys", "thermalservice"),
                            app_pipelines=data.get("renderer_pipelines", []))
            evidence_file.write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
            # Preserve the original broad regression gate, and additionally
            # report actual per-frame deadline misses on the physical device.
            import math
            ordered = sorted(data["frame_ns"])
            def percentile(p):
                return ordered[max(0, math.ceil(len(ordered) * p) - 1)] / 1_000_000 if ordered else None
            physical = {"evidence_kind": "physical_device", "frames": len(ordered),
                        "refresh_hz": data["refresh_hz"], "p50_ms": percentile(.50),
                        "p95_ms": percentile(.95), "p99_ms": percentile(.99),
                        "deadline_miss_rate": data["deadline_misses"] / len(ordered) if ordered else None,
                        "drawer_open_rounds": data.get("drawer_open_rounds", []),
                        "regression_gate": result["status"]}
            Path("ui-motion-physical-summary.json").write_text(json.dumps(physical, indent=2), encoding="utf-8")
            print(json.dumps(physical, ensure_ascii=False))
        if result["status"] != "passed" or result["tests"] != 1 or data["frames"] < 12:
            # Preserve the original failure and frames. A separate sampled
            # diagnostic execution identifies actual CPU stacks; it never
            # supplies acceptance evidence or replaces the failed measurement.
            profile = execute_instrumentation(["UiMotionPerformanceInstrumentedTest"],
                "ui-motion-failure.profile.log", {**renderer_args, "methodTrace": "true"}, timeout=180)
            result["sampled_diagnostic"] = profile
            Path("ui-motion-diagnosis.json").write_text(json.dumps(result, indent=2))
            if profile.get("category") == "diagnostic_timeout":
                print("Independent method sampling exceeded 180 seconds; original frames and failure retained")
                adb("shell", "am", "force-stop", package)
            trace = "ui-motion-failure.trace"
            pulled = subprocess.run(["adb", "pull", DEVICE_ROOT + "/" + trace, trace], check=False)
            if pulled.returncode != 0:
                print("Sampled method trace unavailable; original performance failure retained")
            raise RuntimeError("Actual motion test did not pass")
    finally:
        for suffix in ("txt", "png"):
            subprocess.run(["adb", "pull", DEVICE_ROOT + "/ui-motion-failure." + suffix,
                            "ui-motion-failure." + suffix], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        adb("shell", "am", "force-stop", "io.github.sy220284.codexandroid")
        if not args.physical_device:
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
