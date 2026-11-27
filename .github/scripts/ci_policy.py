#!/usr/bin/env python3
"""Authoritative CI planning and release-input classification."""
import json
import os
import re
import subprocess
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'android-app/tools'))
from device_matrix import CONFIG, matrix, shard_matrix, DIAGNOSTIC_SUITES

POLICY = json.loads((Path(__file__).resolve().parents[1] / "ci" / "scope.json").read_text(encoding="utf-8"))

def matches(path, key):
    group = POLICY[key]
    return path in group["files"] or any(path.startswith(prefix) for prefix in group["prefixes"])

def acceptance_inputs_unchanged(path):
    """Keep the stricter historical reuse boundary independent of scheduling."""
    return matches(path, "maintenance") and not matches(path, "acceptance")


def maintenance_only(paths):
    """Only documentation, repository maintenance and CI/test infrastructure."""
    return all(path not in {"tools/build/native_toolchain.py", "tools/build/native-toolchain.json"} and (matches(path, "maintenance") or matches(path, "ci_maintenance")) for path in paths)

def plan(paths, force=False, runtime=False):
    paths = list(paths)
    full = force or runtime or not maintenance_only(paths)
    return {"full": full, "runtime": full, "maintenance": not full, "changed": paths}

def changed(base, head):
    if not base or base == "0" * 40:
        return ["<initial-or-unknown-base>"]
    if not re.fullmatch(r"[0-9a-f]{40}", base) or not re.fullmatch(r"[0-9a-f]{40}", head):
        raise ValueError("CI 比较必须使用完整提交 SHA")
    return [p.decode("utf-8") for p in subprocess.check_output(
        ["git", "diff", "--no-renames", "--name-only", "-z", base, head, "--"]).split(b"\0") if p]

def diagnostic_candidates(paths, root):
    """Derive affected pages and existing test methods from the current UI coverage registry."""
    root = Path(root)
    registry = json.loads((root / "android-app/tools/ui_interaction_coverage.json").read_text(encoding="utf-8"))
    source = root / "android-app/app/src/androidTest/java"
    classes = {cls:suite for suite, names in DIAGNOSTIC_SUITES.items() for cls in names}
    methods = {}
    for path in source.rglob("*.kt"):
        if path.stem in classes:
            code = path.read_text(encoding="utf-8")
            for name in re.findall(r"\bfun\s+([A-Za-z_]\w*)\s*\(", code):
                methods.setdefault(name, set()).add(classes[path.stem])
    changed_sources = {Path(path).name for path in paths if path.startswith("android-app/app/src/main/")}
    routes = sorted({row["route"] for row in registry["routes"] if row["file"] in changed_sources})
    subpages = [row for row in registry["subpages"] if row["file"] in changed_sources]
    suggested = {(suite, row["test"]) for row in subpages if row.get("test") for suite in methods.get(row["test"], ())}
    direct_classes = {classes[Path(path).stem] for path in paths if path.startswith("android-app/app/src/androidTest/") and Path(path).stem in classes}
    return {"routes":routes, "subpages":sorted(row["id"] for row in subpages),
            "changed_test_suites":sorted(direct_classes),
            "suggested_methods":[{"suite":suite,"method":method} for suite,method in sorted(suggested)],
            "scope":"diagnostic_candidates_only"}

def main():
    result = plan(changed(os.environ.get("CI_BASE", ""), os.environ["CI_HEAD"]),
                  os.environ.get("CI_FORCE") == "true", os.environ.get("CI_RUNTIME") == "true")
    from ci_reuse import GROUPS, make_plan, native_key, required_groups
    source = os.environ["CI_HEAD"]
    actual = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    if actual != source:
        raise ValueError("CI 候选提交与实际检出源码不一致")
    force = os.environ.get("CI_FORCE") == "true"
    required = required_groups(result["full"], result["runtime"], os.environ["CI_EVENT"], os.environ["CI_REF"])
    receipt = make_plan(os.environ["GITHUB_REPOSITORY"], source, required, force)
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as stream:
        print("receipt="+json.dumps(receipt,separators=(",",":")),file=stream)
        for group in GROUPS:
            print(f"run_{group}={str(group in receipt['execute']).lower()}",file=stream)
        print("compile_run_id="+str(receipt["reused"].get("compile", {}).get("run_id", os.environ["GITHUB_RUN_ID"])),file=stream)
        for name, key in (("codex-android-ui-apks","compile_apk_id"), ("ui-apk-integrity","compile_integrity_id")):
            print(key+"="+str(receipt["reused"].get("compile",{}).get("artifacts",{}).get(name,{}).get("id","")),file=stream)
        abis = [abi for group, abi in (("package", "arm64-v8a"), ("runtime", "x86_64"))
                if group in receipt["execute"]]
        print("native_key="+(native_key(source, abis) if abis else ""),file=stream)
        for key, value in {"device_matrix": json.dumps(matrix(), separators=(",", ":")), "device_shards": json.dumps(shard_matrix(), separators=(",", ":")), "device_api": CONFIG["api"], "device_image_api": CONFIG["system_image_api"], "device_sdk_platform": CONFIG["sdk_platform"], "device_runtime_profile": CONFIG["runtime_profile"], "device_release": CONFIG["android_release"], "device_target": CONFIG["target"], "device_ram": CONFIG["ram"], "device_cores": CONFIG["cores"], "device_disk": CONFIG["disk"], "device_mesa_threads": CONFIG["mesa_threads"], "device_emulator_options": CONFIG["emulator_options"]}.items():
            print(f"{key}={value}", file=stream)
        for key in ("full", "runtime", "maintenance"):
            print(f"{key}={str(result[key]).lower()}", file=stream)
    root = Path(__file__).resolve().parents[2]
    impact = diagnostic_candidates(result["changed"], root)
    from ci_reuse import affected
    impact["affected_acceptance_groups"] = sorted(set().union(*(affected(p) for p in result["changed"])))
    result["diagnostic_candidates"] = impact
    print(json.dumps(result | {"acceptance":receipt}, ensure_ascii=False, indent=2))
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary,"a",encoding="utf-8") as out:
            print("## 变更影响与专项诊断建议（不替代正式验收）",file=out)
            print("- 受影响验收组：" + ", ".join(impact["affected_acceptance_groups"]),file=out)
            print("- 关联页面：" + ", ".join(impact["routes"]+impact["subpages"]),file=out)
            print("- 直接修改的专项：" + ", ".join(impact["changed_test_suites"]),file=out)
            for item in impact["suggested_methods"]:
                print("- " + item["suite"] + " / " + item["method"],file=out)

if __name__ == "__main__":
    main()
