#!/usr/bin/env python3
"""Require actual identical, unique successful test sets across all four configurations and eight independent shards."""
import json
import math
import re
import os
import sys
from pathlib import Path
from device_matrix import CONFIG, MODES, PROFILES, dimensions, INDEPENDENT_IDS, independent_device, minimum_tests, SHARDS

def read(path):
    return json.loads(path.read_text(encoding="utf-8"))

def validate_motion(frames):
    values = frames["frame_ns"]
    deadlines = frames["deadline_ns"]
    n = frames["frames"]
    if (type(n) is not int or n < 12 or len(values) != n or len(deadlines) != n
        or frames["listener_dropped"] != 0
        or "Pipeline=Skia (OpenGL)" not in frames["renderer_pipelines"]
        or any(type(x) is not int or x <= 0 for x in values)
        or any(type(x) is not int for x in deadlines)):
        raise ValueError("Invalid raw motion samples or actual renderer")
    p95 = sorted(values)[math.ceil(n * .95) - 1] / 1_000_000
    slow = sum(x > 100_000_000 for x in values)
    if (not math.isclose(frames["p95_ms"], p95, rel_tol=1e-9, abs_tol=1e-9)
        or frames["very_slow_frames"] != slow or p95 > 120 or slow / n > .15):
        raise ValueError("Raw motion samples exceed budget or disagree with reported metrics")

def validate(root, source):
    expected = {(profile, mode) for profile in PROFILES for mode in MODES}
    expected_dirs = {f"ui-proof-{p}-{m}" for p,m in expected}
    present_dirs = {p.name for p in root.iterdir()} if root.is_dir() else set()
    failures = []
    observations = []
    if present_dirs != expected_dirs:
        failures.append(f"Missing or unexpected device proof: missing={sorted(expected_dirs-present_dirs)}, unexpected={sorted(present_dirs-expected_dirs)}")
    reference = None
    apk_reference = None
    run_ids = set()
    count = 0
    for profile, mode in sorted(expected):
        try:
            directory = root/f"ui-proof-{profile}-{mode}"
            report = read(directory/f"ui-{mode}-diagnosis.json")
            if (report.get("status") != "passed" or report.get("commit") != source
                or report.get("profile") != profile or report.get("mode") != mode
                or report.get("android_api") != CONFIG["api"]
                or report.get("page_size") != CONFIG["page_size"]
                or not report.get("build_fingerprint")):
                raise ValueError(f"{profile}/{mode}: wrong or failed device proof")
            if any(report.get(k) != v for k,v in dimensions(profile,mode).items()):
                raise ValueError(f"{profile}/{mode}: wrong display configuration")
            apk = report["tested_apk_sha256"]
            sources = report["shard_source_commits"]
            origins = report["shard_origins"]
            if (set(apk) != {"debug/app-debug.apk","androidTest/debug/app-debug-androidTest.apk"}
                or not all(re.fullmatch("[0-9a-f]{64}", x) for x in apk.values())
                or set(sources) != set(SHARDS) or set(origins) - set(SHARDS)
                or any(sources[s] != source for s in set(SHARDS)-set(origins))
                or any(origins[s]["source"] != sources[s] or origins[s]["input"]["apk"] != apk for s in origins)):
                raise ValueError("Invalid APK or shard origin proof")
            if apk_reference is None:
                apk_reference = apk
            if apk != apk_reference:
                raise ValueError("Device matrix tested different APK bytes")
            run_id = report.get("run_id")
            if not run_id or run_id in run_ids:
                raise ValueError("Duplicate UI execution ID")
            shard_ids = report["shard_run_ids"]
            if set(shard_ids) != set(SHARDS) or len(set(shard_ids.values())) != len(SHARDS) or any(not x or x in run_ids or x == run_id for x in shard_ids.values()):
                raise ValueError("Missing or duplicate shard execution ID")
            run_ids.update([run_id, *shard_ids.values()])
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
            observation = {"profile": profile, "mode": mode, "blocking": False,
                           "screenshot_collection": report.get("screenshot_observation", {"missing": report.get("missing_screens"), "exit": report.get("screenshot_collection_exit")})}
            try:
                visual = read(directory/"ui-visual-evidence/report.json")
                observation["visual"] = visual
            except (OSError, ValueError, KeyError, TypeError) as exc:
                observation["visual"] = {"status": "unavailable", "error": str(exc)}
            if mode == "baseline":
                try:
                    motion = read(directory/"ui-motion-diagnosis.json")
                    frames = read(directory/"ui-motion-performance.json")
                    observation["motion"] = {"status": motion.get("status"), "frames": frames.get("frames"),
                                             "p95_ms": frames.get("p95_ms"), "very_slow_frames": frames.get("very_slow_frames")}
                    validate_motion(frames)
                    if motion.get("status") != "passed" or motion.get("tests") != 1:
                        raise ValueError("Motion observation did not pass")
                except (OSError, ValueError, KeyError, TypeError) as exc:
                    observation.setdefault("motion", {})["status"] = "failed_or_unavailable"
                    observation["motion"]["error"] = str(exc)
                    failures.append(f"{profile}/{mode}: required motion proof failed: {exc}")
                if PROFILES[profile]["self_update"]:
                    update = read(directory/"self-update-open.json")
                    if update.get("status") != "passed":
                        raise ValueError("System installer proof did not pass")
            observations.append(observation)
            count += len(ids)
        except (OSError, ValueError, KeyError, TypeError) as exc:
            failures.append(f"{profile}/{mode}: {exc}")
    if failures:
        raise ValueError("\n".join(failures))
    return {"device_configurations": len(expected), "unique_tests":len(reference)+len(INDEPENDENT_IDS), "executions":count, "observations":observations}

if __name__ == "__main__":
    result = validate(Path(sys.argv[1]), os.environ["TEST_SOURCE_SHA"])
    print(json.dumps(result, ensure_ascii=False))
