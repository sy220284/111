#!/usr/bin/env python3
"""Opt-in post-release pressure measurement. Results never participate in release gates."""
import argparse
import hashlib
import json
import os
import subprocess
import uuid
from pathlib import Path
from run_ui_device_checks import adb, execute_instrumentation, PACKAGE, DEVICE_ROOT

TEST_CLASS = "PostReleasePressureInstrumentedTest"

def digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--physical-serial", required=True)
    parser.add_argument("--released-apk", type=Path, required=True,
                        help="Original published APK, used to verify the installed app")
    parser.add_argument("--test-apk", type=Path, required=True,
                        help="Same-source instrumentation APK signed with the published app's key")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--stream-seconds", type=int, default=1800)
    parser.add_argument("--stream-rate", type=int, default=100)
    args = parser.parse_args()
    if not 8 <= args.stream_seconds <= 1800 or not 10 <= args.stream_rate <= 100:
        parser.error("invalid pressure workload")
    os.environ["ANDROID_SERIAL"] = args.physical_serial
    run_id = uuid.uuid4().hex
    out = args.output / run_id
    out.mkdir(parents=True, exist_ok=False)
    result = {"run_id": run_id, "purpose": "post_release_observation", "release_gate": False,
              "target_seconds": args.stream_seconds, "target_rate": args.stream_rate,
              "status": "not_run", "errors": []}
    remote = DEVICE_ROOT + "/post-release-pressure-" + run_id + ".json"
    try:
        if adb("get-state") != "device" or adb("shell", "getprop", "ro.product.cpu.abi") != "arm64-v8a" or any(
                adb("shell", "getprop", key) == "1" for key in ("ro.kernel.qemu", "ro.boot.qemu")):
            raise RuntimeError("需要独立 ARM64 实体测试设备")
        result["device_model"] = adb("shell", "getprop", "ro.product.model")
        result["android_api"] = adb("shell", "getprop", "ro.build.version.sdk")
        expected = digest(args.released_apk)
        result["released_apk_sha256"] = expected
        result["test_apk_sha256"] = digest(args.test_apk)
        installed = adb("shell", "pm", "path", PACKAGE).splitlines()
        if len(installed) != 1 or not installed[0].startswith("package:"):
            raise RuntimeError("设备未安装可核验的单一正式 APK")
        local = out / "installed-check.apk"
        try:
            subprocess.run(["adb", "pull", installed[0].removeprefix("package:"), str(local)], check=True)
            result["installed_apk_sha256"] = digest(local)
            if result["installed_apk_sha256"] != expected:
                raise RuntimeError("已安装 APK 与指定发布 APK 摘要不一致")
        finally:
            local.unlink(missing_ok=True)
        # Install only the test package. Android enforces matching target signature
        # when instrumentation starts. Never install or replace the released app.
        subprocess.run(["adb", "install", "-r", "-t", str(args.test_apk)], check=True)
        result["status"] = "running"
        result["instrumentation"] = execute_instrumentation([TEST_CLASS], out / "instrumentation.log", {
            "postReleasePressure": "true", "pressureRunId": run_id,
            "streamSeconds": str(args.stream_seconds), "streamRate": str(args.stream_rate)})
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        result["errors"].append(str(error))
    finally:
        if result["status"] == "running":
            try:
                subprocess.run(["adb", "pull", remote, str(out / "measurements.json")], check=True)
                report = json.loads((out / "measurements.json").read_text(encoding="utf-8"))
                if report.get("run_id") != run_id or report.get("release_gate") is not False:
                    raise ValueError("专项报告标识不匹配")
                result["measurements"] = "measurements.json"
                result["status"] = report.get("status", "partial")
                if result.get("instrumentation", {}).get("status") != "passed":
                    result["status"] = "partial"
            except (OSError, ValueError, subprocess.SubprocessError) as error:
                result["status"] = "incomplete"
                result["errors"].append(str(error))
        (out / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(result, ensure_ascii=False, indent=2))
        print("结果目录:", out)
    # This manual collector records unsuccessful or missing samples too. No
    # performance threshold or report is consumed by build or release automation.

if __name__ == "__main__":
    main()
