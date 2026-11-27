"""Single device test configuration for CI, runtime, screenshot evidence and release acceptance."""
import argparse
import json
import os
import re
import subprocess
from pathlib import Path

CONFIG = json.loads(Path(__file__).with_name("device-matrix.json").read_text(encoding="utf-8"))
PROFILES = {p["id"]: p for p in CONFIG["profiles"]}
API = CONFIG["api"]
RELEASE = CONFIG["android_release"]

def dimensions(profile, mode):
    p = PROFILES[profile]
    if mode not in ("baseline", "adaptive"):
        raise ValueError(f"未知设备验收模式：{mode}")
    spec = p if mode == "baseline" else p["adaptive"]
    return {k: spec[k] for k in ("width_px", "height_px", "density_dpi")} | {
        "font_scale": 1.0 if mode == "baseline" else spec["font_scale"]}

MODES = ("baseline", "adaptive")
# Keep complete test classes together; split the 68 navigation cases from the other 84 UI cases.
SHARDS = {
    "navigation": ("UiNavigationInstrumentedTest",),
    "components": ("UiPopulatedSubpagesInstrumentedTest", "CodexInteractionComponentsInstrumentedTest",
                   "CodexOverlayInstrumentedTest", "EngineSettingInstrumentedTest",
                   "VideoAttachmentPreparationInstrumentedTest"),
}

# These three exercise Android Intent/PackageManager/FileProvider, with no Compose or screenshots.
INDEPENDENT_CLASS = "SelfUpdateInstallerIntentInstrumentedTest"
INDEPENDENT_IDS = frozenset("io.github.sy220284.codexandroid."+INDEPENDENT_CLASS+"#"+name for name in (
    "systemInstallerSurvivesReplacingTheCallingApplication",
    "postInstallOpenResolvesToTheRealLauncherActivity",
    "updateProviderSharesOnlyTheVerifiedUpdateCache",
))

def independent_device(profile,mode):
    return bool(PROFILES[profile]["self_update"] and mode=="baseline")

def minimum_tests(profile,mode):
    return CONFIG["minimum_ui_tests"]-(0 if independent_device(profile,mode) else len(INDEPENDENT_IDS))


def matrix():
    return {"include": [{"profile": p["id"], "mode": mode, **dimensions(p["id"], "baseline"),
                          "self_update": p["self_update"] and mode == "baseline",
                          "motion": mode == "baseline", "api": API,
                          "android_release": RELEASE} for p in CONFIG["profiles"] for mode in MODES]}

def shard_matrix():
    return {"include": [row | {"shard": shard,
                                "motion": row["motion"] and shard == "navigation",
                                "self_update": row["self_update"] and shard == "components"}
                        for row in matrix()["include"] for shard in SHARDS]}


def shard_classes(profile, mode, shard):
    return list(SHARDS[shard]) + ([INDEPENDENT_CLASS] if shard == "components" and independent_device(profile, mode) else [])


def shard_job_name(profile, mode, shard):
    return f"{job_name(profile, mode)} · {shard}"


def collect_job_name(profile, mode):
    return f"UI 分流证据汇总 · {profile} {mode}"


def job_name(profile, mode):
    return f"Android {RELEASE} (API {API}) · {profile} {mode} UI 交互与截图验收"

def configure_avd(path, profile):
    spec = dimensions(profile, "baseline")
    fields = {
        "hw.lcd.width": str(spec["width_px"]),
        "hw.lcd.height": str(spec["height_px"]),
        "hw.lcd.density": str(spec["density_dpi"]),
        "skin.name": f"{spec['width_px']}x{spec['height_px']}",
        "skin.path": "_no_skin",
        "showDeviceFrame": "no",
    }
    original = path.read_text(encoding="utf-8").splitlines()
    lines = [line for line in original if line.split("=", 1)[0].strip() not in fields]
    lines.extend(f"{key}={value}" for key, value in fields.items())
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps({"avd": str(path), "profile": profile, **fields}, ensure_ascii=False))


def verify_avd_metadata(root, name, sdk, tools_revision):
    def properties(path):
        return {key.strip(): value.strip() for line in path.read_text(encoding="utf-8").splitlines()
                if "=" in line for key,value in [line.split("=",1)]}
    tool=sdk/"cmdline-tools"/tools_revision/"source.properties"
    if properties(tool).get("Pkg.Revision") != tools_revision:
        raise ValueError("Android command-line tools revision mismatch")
    metadata=properties(root/(name+".ini"))
    expected="android-"+CONFIG["system_image_api"]
    if metadata.get("target") != expected:
        raise ValueError("AVD target mismatch: expected "+expected+", got "+str(metadata.get("target")))
    config=properties(root/(name+".avd")/"config.ini")
    image=Path(config.get("image.sysdir.1",""))
    if not image.is_absolute():
        image=sdk/image
    actual=sdk/"system-images"/expected/CONFIG["target"]/CONFIG["arch"]
    if image.resolve() != actual.resolve() or not image.is_dir():
        raise ValueError("AVD image does not match the locked system image")
    return {"target": expected, "command_line_tools": tools_revision, "image": str(actual)}


def verify_resources(guest_cpus, meminfo):
    """Verify the booted guest, allowing memory reserved by the kernel."""
    match = re.search(r"(?m)^MemTotal:\s+(\d+)\s+kB\s*$", meminfo)
    if not match:
        raise ValueError("Android guest did not report MemTotal")
    memory_kib = int(match[1])
    requested_kib = int(CONFIG["ram"].removesuffix("M")) * 1024
    result = {
        "requested_cores": CONFIG["cores"], "actual_cores": int(guest_cpus),
        "requested_ram_mib": requested_kib // 1024,
        "actual_memtotal_kib": memory_kib,
        "configured_disk": CONFIG["disk"],
    }
    # MemTotal excludes reserved kernel memory; 7/8 of the allocated RAM
    # must remain visible, and an under-provisioned 4 GiB guest must fail.
    result["status"] = "passed" if (result["actual_cores"] == CONFIG["cores"]
        and requested_kib * 7 // 8 <= memory_kib <= requested_kib) else "failed"
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--configure-avd", choices=sorted(PROFILES))
    mode.add_argument("--verify-resources", type=Path)
    mode.add_argument("--verify-avd-metadata", action="store_true")
    parser.add_argument("--avd-name", default="test")
    args = parser.parse_args()
    if args.verify_avd_metadata:
        root=Path(os.environ["ANDROID_AVD_HOME"])
        sdk=Path(os.environ["CODEX_EMULATOR_SDK"])
        print(json.dumps(verify_avd_metadata(root,args.avd_name,sdk,CONFIG["command_line_tools"])))
        raise SystemExit(0)
    if args.verify_resources:
        cpus = subprocess.check_output(["adb", "shell", "getconf", "_NPROCESSORS_ONLN"], text=True).strip()
        memory = subprocess.check_output(["adb", "shell", "cat", "/proc/meminfo"], text=True)
        result = verify_resources(cpus, memory)
        args.verify_resources.parent.mkdir(parents=True, exist_ok=True)
        args.verify_resources.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(result), flush=True)
        raise SystemExit(result["status"] != "passed")
    root = Path(os.environ.get("ANDROID_AVD_HOME") or Path.home() / ".android/avd")
    configure_avd(root / (args.avd_name + ".avd") / "config.ini", args.configure_avd)
