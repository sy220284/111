#!/usr/bin/env python3
"""Authoritative CI planning and release-input classification."""
import json
import os
import re
import subprocess
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'android-app/tools'))
from device_matrix import CONFIG, matrix, shard_matrix

POLICY = json.loads((Path(__file__).resolve().parents[1] / "ci" / "scope.json").read_text(encoding="utf-8"))

def matches(path, key):
    group = POLICY[key]
    return path in group["files"] or any(path.startswith(prefix) for prefix in group["prefixes"])

def maintenance_only(paths):
    return all(matches(path, "maintenance") and not matches(path, "acceptance") for path in paths)

def plan(paths, force=False, runtime=False):
    paths = list(paths)
    runtime = runtime or any(matches(path, "runtime") for path in paths)
    full = force or runtime or not maintenance_only(paths)
    return {"full": full, "runtime": runtime, "maintenance": not full, "changed": paths}

def changed(base, head):
    if not base or base == "0" * 40:
        return ["<initial-or-unknown-base>"]
    if not re.fullmatch(r"[0-9a-f]{40}", base) or not re.fullmatch(r"[0-9a-f]{40}", head):
        raise ValueError("CI 比较必须使用完整提交 SHA")
    return [p.decode("utf-8") for p in subprocess.check_output(
        ["git", "diff", "--no-renames", "--name-only", "-z", base, head, "--"]).split(b"\0") if p]

def main():
    result = plan(changed(os.environ.get("CI_BASE", ""), os.environ["CI_HEAD"]),
                  os.environ.get("CI_FORCE") == "true", os.environ.get("CI_RUNTIME") == "true")
    from ci_reuse import GROUPS, make_plan, native_key, required_groups
    source = os.environ["CI_HEAD"]
    force = os.environ.get("CI_FORCE") == "true"
    required = required_groups(result["full"], result["runtime"], os.environ["CI_EVENT"], os.environ["CI_REF"])
    receipt = make_plan(os.environ["GITHUB_REPOSITORY"], source, required, force)
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as stream:
        print("receipt="+json.dumps(receipt,separators=(",",":")),file=stream)
        for group in GROUPS:
            print(f"run_{group}={str(group in receipt['execute']).lower()}",file=stream)
        print("native_key="+(native_key(source) if "package" in receipt["execute"] else ""),file=stream)
        for key, value in {"device_matrix": json.dumps(matrix(), separators=(",", ":")), "device_shards": json.dumps(shard_matrix(), separators=(",", ":")), "device_api": CONFIG["api"], "device_image_api": CONFIG["system_image_api"], "device_sdk_platform": CONFIG["sdk_platform"], "device_runtime_profile": CONFIG["runtime_profile"], "device_release": CONFIG["android_release"], "device_target": CONFIG["target"], "device_ram": CONFIG["ram"], "device_cores": CONFIG["cores"], "device_disk": CONFIG["disk"], "device_mesa_threads": CONFIG["mesa_threads"], "device_emulator_options": CONFIG["emulator_options"]}.items():
            print(f"{key}={value}", file=stream)
        for key in ("full", "runtime", "maintenance"):
            print(f"{key}={str(result[key]).lower()}", file=stream)
    print(json.dumps(result | {"acceptance":receipt}, ensure_ascii=False, indent=2))

if __name__ == "__main__":
    main()
