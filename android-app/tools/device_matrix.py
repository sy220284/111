"""Single device test configuration for CI, runtime, screenshot evidence and release acceptance."""
import json
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
