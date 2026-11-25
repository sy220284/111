#!/usr/bin/env python3
"""Single source for all mandatory and optional device screenshot declarations."""
import json
import re
from pathlib import Path

MANIFEST_RELATIVE_PATH = Path("docs/ui-assets/素材接入清单.json")


def required_screens(manifest):
    screens = []
    for entry in manifest["routes"]:
        if not entry.get("screens"):
            raise ValueError("路由缺少真实截图登记：" + entry["route"])
        screens.extend(entry["screens"])
    screens.extend(manifest["requiredStateScreens"])
    return validate_screens(screens)


def validate_screens(screens):
    if len(set(screens)) != len(screens):
        raise ValueError("设备截图重复登记")
    if any(not re.fullmatch(r"[a-z0-9][a-z0-9_-]*", screen) for screen in screens):
        raise ValueError("设备截图名称无效")
    return screens


def declared_screens(manifest):
    return validate_screens(required_screens(manifest) + manifest["optionalStateScreens"])


def load_evidence_manifest(repository):
    return json.loads((Path(repository) / MANIFEST_RELATIVE_PATH).read_text(encoding="utf-8"))
