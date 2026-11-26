#!/usr/bin/env python3
"""Require actual identical, unique successful test sets across all six clean devices."""
import json
import os
import sys
from pathlib import Path
from device_matrix import CONFIG, MODES, PROFILES, dimensions, INDEPENDENT_IDS, independent_device, minimum_tests

def read(path):
    return json.loads(path.read_text(encoding="utf-8"))

def validate(root, source):
    expected = {(profile, mode) for profile in PROFILES for mode in MODES}
    expected_dirs = {f"ui-proof-{p}-{m}" for p,m in expected}
    present_dirs = {p.name for p in root.iterdir()} if root.is_dir() else set()
    failures = []
    if present_dirs != expected_dirs:
        failures.append(f"Missing or unexpected device proof: missing={sorted(expected_dirs-present_dirs)}, unexpected={sorted(present_dirs-expected_dirs)}")
    reference = None
    run_ids = set()
    count = 0
    for profile, mode in sorted(expected):
        try:
            directory = root/f"ui-proof-{profile}-{mode}"
            report = read(directory/f"ui-{mode}-diagnosis.json")
            visual = read(directory/"ui-visual-evidence/report.json")
            if (report.get("status") != "passed" or report.get("commit") != source
                or report.get("profile") != profile or report.get("mode") != mode
                or report.get("android_api") != CONFIG["api"]
                or report.get("page_size") != CONFIG["page_size"]
                or not report.get("build_fingerprint")
                or report.get("missing_screens") != [] or report.get("screenshot_collection_exit") != 0):
                raise ValueError(f"{profile}/{mode}: wrong or failed device proof")
            if any(report.get(k) != v for k,v in dimensions(profile,mode).items()):
                raise ValueError(f"{profile}/{mode}: wrong display configuration")
            run_id = report.get("run_id")
            if not run_id or run_id in run_ids:
                raise ValueError("Duplicate UI execution ID")
            run_ids.add(run_id)
            ids = report["test_ids"]
            if len(ids) < minimum_tests(profile,mode) or len(ids) != report["tests"] or len(set(ids)) != len(ids):
                raise ValueError("Missing or duplicate test completions")
            actual=set(ids)
            platform={i for i in actual if ".SelfUpdateInstallerIntentInstrumentedTest#" in i}
            if platform != (INDEPENDENT_IDS if independent_device(profile,mode) else set()):
                raise ValueError(f"{profile}/{mode}: missing or unexpected screen-independent installer cases")
            visual_ids=actual-platform
            if reference is None:
                reference=visual_ids
            if visual_ids != reference:
                raise ValueError(f"{profile}/{mode}: different visual test identities")
            if visual.get("status") not in {"compared","baseline_pending"} or visual.get("structural_errors") != []:
                raise ValueError(f"{profile}/{mode}: invalid visual proof")
            if mode == "baseline":
                motion = read(directory/"ui-motion-diagnosis.json")
                frames = read(directory/"ui-motion-performance.json")
                if motion.get("status") != "passed" or motion.get("tests") != 1 or frames.get("frames",0) < 12:
                    raise ValueError(f"{profile}: motion proof missing or failed")
                if PROFILES[profile]["self_update"]:
                    update = read(directory/"self-update-open.json")
                    if update.get("status") != "passed":
                        raise ValueError("System installer proof did not pass")
            count += len(ids)
        except (OSError, ValueError, KeyError, TypeError) as exc:
            failures.append(f"{profile}/{mode}: {exc}")
    if failures:
        raise ValueError("\n".join(failures))
    return {"device_configurations": len(expected), "unique_tests":len(reference)+len(INDEPENDENT_IDS), "executions":count}

if __name__ == "__main__":
    result = validate(Path(sys.argv[1]), os.environ["TEST_SOURCE_SHA"])
    print(json.dumps(result, ensure_ascii=False))
