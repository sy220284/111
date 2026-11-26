#!/usr/bin/env python3
"""Join disjoint, fresh Android test shards for one exact device configuration."""
import argparse
import hashlib
import json
import os
import shutil
import struct
import sys
from pathlib import Path

from collect_ui_screenshots import REQUIRED_SCREENS, valid_png, write_evidence
from device_matrix import CONFIG, PROFILES, MODES, SHARDS, dimensions, minimum_tests, shard_classes
from run_ui_device_checks import PACKAGE
sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'.github/scripts'))
from ui_shard_reuse import INPUT, ORIGIN, check_input, verify_origin


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def require(condition, message):
    if not condition:
        raise ValueError(message)


def merge(root, output, profile, mode, source, compile_proof=None, repo=None):
    names = {s: f"ui-shard-{profile}-{mode}-{s}" for s in SHARDS}
    require(root.is_dir() and {p.name for p in root.iterdir()} == set(names.values()), "Missing or unexpected shard artifacts")
    folder = "ui-screenshots" if mode == "baseline" else "ui-screenshots-adaptive"
    destination = output / folder
    destination.mkdir(parents=True, exist_ok=False)
    all_ids, screens, discovered, run_ids = set(), {}, set(), {}
    reports = []
    shard_sources, origins = {}, {}
    apk_inputs = None
    for shard, name in names.items():
        directory = root / name
        report = read(directory / f"ui-{mode}-diagnosis.json")
        manifest = read(directory / folder / "manifest.json")
        current = read(directory/INPUT)
        check_input(current,source,profile,mode,shard)
        if apk_inputs is None:
            apk_inputs=current["apk"]
        require(apk_inputs==current["apk"], "Shards tested different APK bytes")
        original_source=source
        if (directory/ORIGIN).is_file():
            require(bool(compile_proof and repo), "Missing trusted compile proof for reused shard")
            origin=read(directory/ORIGIN)
            original_source=verify_origin(repo,compile_proof,source,profile,mode,shard,origin,current)
            origins[shard]=origin
        shard_sources[shard]=original_source
        expected = dimensions(profile, mode) | {
            "profile": profile, "mode": mode, "shard": shard, "commit": original_source,
            "android_api": CONFIG["api"], "page_size": CONFIG["page_size"]}
        for record in (report, manifest):
            require(all(record.get(k) == v for k,v in expected.items()), f"{shard}: source or device mismatch")
            require(bool(record.get("build_fingerprint")), f"{shard}: missing Android fingerprint")
        require(all(report.get(k) == manifest.get(k) for k in ("build_fingerprint", "physical_size", "physical_density_dpi", "android_release", "width_dp", "height_dp")), f"{shard}: screenshot and test device identity mismatch")
        physical = dimensions(profile, "baseline")
        require(report["physical_size"] == f"{physical['width_px']}x{physical['height_px']}" and report["physical_density_dpi"] == physical["density_dpi"], f"{shard}: wrong physical display")
        require(report["status"] == "passed" and report["missing_screens"] == [] and report["screenshot_collection_exit"] == 0, f"{shard}: failed test or collection")
        run_id = report["run_id"]
        require(run_id and run_id not in run_ids.values() and manifest["run_id"] == run_id, f"{shard}: duplicate or wrong run ID")
        run_ids[shard] = run_id
        ids = report["test_ids"]
        require(len(ids) == report["tests"] == len(set(ids)) and not all_ids.intersection(ids), f"{shard}: duplicate or missing test identities")
        require({i.split("#",1)[0] for i in ids} == {PACKAGE + "." + c for c in shard_classes(profile, mode, shard)}, f"{shard}: wrong test classes")
        all_ids.update(ids)
        present = manifest["present"]
        require(bool(present) and set(manifest["required"]) == set(present), f"{shard}: missing partial screenshot declarations")
        require(not manifest["missing"] and len(set(present)) == len(present) and set(present) == set(manifest["discovered"]), f"{shard}: incomplete screenshots")
        require(set(present) == set(manifest["screenshot_dimensions"]), f"{shard}: screenshot dimension registry mismatch")
        for screen in present:
            require(screen not in screens, f"{shard}: duplicate screenshot name {screen}")
            require(screen and all(c in "abcdefghijklmnopqrstuvwxyz0123456789_-" for c in screen), f"{shard}: invalid screenshot name")
            path = directory / folder / (screen + "-android.png")
            require(valid_png(path), f"{shard}: invalid PNG {screen}")
            with path.open("rb") as png:
                png.seek(16)
                width, height = struct.unpack(">II", png.read(8))
            measured = {"width_px": width, "height_px": height}
            require(measured == manifest["screenshot_dimensions"][screen], f"{shard}: wrong screenshot dimensions")
            shutil.copyfile(path, destination / path.name)
            screens[screen] = measured
        discovered.update(manifest["discovered"])
        reports.append(report)
        # Preserve specialty evidence and fail on ambiguous ownership.
        for pattern in ("ui-motion*", "self-update*"):
            for path in directory.glob(pattern):
                require(mode == "baseline" and ((pattern == "ui-motion*" and shard == "navigation") or (pattern == "self-update*" and shard == "components" and PROFILES[profile]["self_update"])), f"Unexpected specialty evidence: {path.name}")
                target = output / path.name
                require(not target.exists(), f"Duplicate specialty evidence: {path.name}")
                shutil.copyfile(path, target)
    require(len(all_ids) >= minimum_tests(profile, mode), "Combined UI test count below floor")
    require(len({r["build_fingerprint"] for r in reports}) == 1, "Shards ran different system images")
    missing = sorted(set(REQUIRED_SCREENS) - screens.keys())
    require(not missing, f"Missing required screenshots: {missing}")
    run_id = hashlib.sha256("|".join(run_ids[s] for s in SHARDS).encode()).hexdigest()
    present = sorted(screens)
    write_evidence(destination, present, screens, [], sorted(discovered), REQUIRED_SCREENS)
    report = dict(reports[0])
    report.pop("shard")
    report.update(commit=source, run_id=run_id, shard_run_ids=run_ids, shard_source_commits=shard_sources,
                  shard_origins=origins, tested_apk_sha256=apk_inputs, tests=len(all_ids), test_ids=sorted(all_ids),
                  exit_code=0, failures=[])
    manifest_path = destination / "manifest.json"
    manifest = read(manifest_path)
    manifest.update({k: report[k] for k in ("profile", "mode", "commit", "run_id", "shard_run_ids", "android_api", "android_release", "page_size", "build_fingerprint", "physical_size", "physical_density_dpi", "width_px", "height_px", "density_dpi", "font_scale", "width_dp", "height_dp")})
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output / f"ui-{mode}-diagnosis.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--profile", choices=PROFILES, required=True)
    parser.add_argument("--mode", choices=MODES, required=True)
    args = parser.parse_args()
    result = merge(args.root, Path.cwd(), args.profile, args.mode, os.environ["TEST_SOURCE_SHA"],
                   json.loads(os.environ.get("CI_COMPILE_PROOF") or "null"),os.environ.get("GITHUB_REPOSITORY"))
    print(json.dumps({"tests": result["tests"], "shard_run_ids": result["shard_run_ids"]}, ensure_ascii=False))
