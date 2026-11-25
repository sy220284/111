"""Single device test configuration for CI, runtime, screenshot evidence and release acceptance."""
import argparse
import json
import os
from pathlib import Path

CONFIG = json.loads(Path(__file__).with_name("device-matrix.json").read_text(encoding="utf-8"))
PROFILES = {p["id"]: p for p in CONFIG["profiles"]}
API = CONFIG["api"]
RELEASE = CONFIG["android_release"]

def dimensions(profile, mode):
    p = PROFILES[profile]
    spec = p if mode == "baseline" else p["adaptive"]
    return {k: spec[k] for k in ("width_px", "height_px", "density_dpi")} | {
        "font_scale": 1.0 if mode == "baseline" else spec["font_scale"]}

def matrix():
    return {"include": [{"profile": p["id"], "width_px": p["width_px"],
                          "height_px": p["height_px"], "density_dpi": p["density_dpi"],
                          "self_update": p["self_update"], "api": API,
                          "android_release": RELEASE} for p in CONFIG["profiles"]]}

def job_name(profile):
    return f"Android {RELEASE} (API {API}) · {profile} UI 交互与截图验收"

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


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--configure-avd", choices=sorted(PROFILES), required=True)
    parser.add_argument("--avd-name", default="test")
    args = parser.parse_args()
    root = Path(os.environ.get("ANDROID_AVD_HOME") or Path.home() / ".android/avd")
    configure_avd(root / (args.avd_name + ".avd") / "config.ini", args.configure_avd)
