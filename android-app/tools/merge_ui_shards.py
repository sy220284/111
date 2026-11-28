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
from device_matrix import CONFIG, PROFILES, MODES, SHARDS, dimensions, minimum_tests, shard_classes, audit_theme
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
    all_ids, screens, discovered, run_ids = set(), {}, set(), {}
    screenshot_errors = []
    try:
        destination.mkdir(parents=True, exist_ok=False)
    except OSError as exc:
        screenshot_errors.append("Screenshot directory: " + str(exc))
    reports = []
    shard_sources, origins = {}, {}
    apk_inputs = None
    for shard, name in names.items():
        directory = root / name
        report = read(directory / f"ui-{mode}-diagnosis.json")
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
        require(all(report.get(k) == v for k,v in expected.items()), f"{shard}: source or device mismatch")
        require(bool(report.get("build_fingerprint")), f"{shard}: missing Android fingerprint")
        physical = dimensions(profile, "baseline")
        require(report["physical_size"] == f"{physical['width_px']}x{physical['height_px']}" and report["physical_density_dpi"] == physical["density_dpi"], f"{shard}: wrong physical display")
        require(report["status"] == "passed", f"{shard}: failed functional test")
        run_id = report["run_id"]
        require(run_id and run_id not in run_ids.values(), f"{shard}: duplicate or missing run ID")
        run_ids[shard] = run_id
        ids = report["test_ids"]
        require(len(ids) == report["tests"] == len(set(ids)) and not all_ids.intersection(ids), f"{shard}: duplicate or missing test identities")
        require({i.split("#",1)[0] for i in ids} == {PACKAGE + "." + c for c in shard_classes(profile, mode, shard)}, f"{shard}: wrong test classes")
        all_ids.update(ids)
        # Screenshot ownership and content remain checked, but evidence
        # defects are recorded independently of the functional test receipt.
        if report.get("missing_screens") or report.get("screenshot_collection_exit") != 0:
            screenshot_errors.append(f"{shard}: incomplete capture: {report.get('missing_screens')}, exit={report.get('screenshot_collection_exit')}")
        screenshot_errors.extend(f"{shard}: {x}" for x in report.get("screenshot_observation", {}).get("errors", []))
        try:
            manifest = read(directory / folder / "manifest.json")
            require(report.get("audit_theme") == manifest.get("audit_theme") == audit_theme(mode), f"{shard}: wrong or missing audit theme")
            require(all(manifest.get(k) == v for k,v in expected.items()), f"{shard}: screenshot source or device mismatch")
            require(all(report.get(k) == manifest.get(k) for k in ("build_fingerprint", "physical_size", "physical_density_dpi", "android_release", "width_dp", "height_dp", "run_id")), f"{shard}: screenshot and test identity mismatch")
            present = manifest["present"]
            if (not present or set(manifest["required"]) != set(present) or manifest["missing"]
                    or len(set(present)) != len(present) or set(present) != set(manifest["discovered"])
                    or set(present) != set(manifest["screenshot_dimensions"])):
                screenshot_errors.append(f"{shard}: incomplete screenshot declarations or dimensions")
            for screen in present:
                try:
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
                except (OSError, ValueError, KeyError, TypeError, struct.error) as exc:
                    screenshot_errors.append(str(exc))
            discovered.update(manifest["discovered"])
        except (OSError, ValueError, KeyError, TypeError) as exc:
            screenshot_errors.append(f"{shard}: {exc}")
        reports.append(report)
        # Preserve specialty evidence and fail on ambiguous ownership.
        for pattern in ("ui-motion*", "self-update*"):
            for path in directory.glob(pattern):
                advisory = pattern == "ui-motion*" or path.suffix == ".png"
                try:
                    require(mode == "baseline" and ((pattern == "ui-motion*" and shard == "navigation") or (pattern == "self-update*" and shard == "components" and PROFILES[profile]["self_update"])), f"Unexpected specialty evidence: {path.name}")
                    target = output / path.name
                    require(not target.exists(), f"Duplicate specialty evidence: {path.name}")
                    shutil.copyfile(path, target)
                except (OSError, ValueError) as exc:
                    if not advisory:
                        raise
                    screenshot_errors.append("Advisory evidence: " + str(exc))
    require(len(all_ids) >= minimum_tests(profile, mode), "Combined UI test count below floor")
    require(len({r["build_fingerprint"] for r in reports}) == 1, "Shards ran different system images")
    missing = sorted(set(REQUIRED_SCREENS) - screens.keys())
    if missing:
        screenshot_errors.append(f"Missing expected screenshots: {missing}")
    run_id = hashlib.sha256("|".join(run_ids[s] for s in SHARDS).encode()).hexdigest()
    present = sorted(screens)
    try:
        write_evidence(destination, present, screens, missing, sorted(discovered), REQUIRED_SCREENS)
    except (OSError, ValueError, KeyError, TypeError) as exc:
        screenshot_errors.append("Gallery: " + str(exc))
    report = dict(reports[0])
    report.pop("shard")
    report.update(commit=source, run_id=run_id, shard_run_ids=run_ids, shard_source_commits=shard_sources,
                  shard_origins=origins, tested_apk_sha256=apk_inputs, tests=len(all_ids), test_ids=sorted(all_ids),
                  exit_code=0, failures=[])
    manifest_path = destination / "manifest.json"
    try:
        manifest = read(manifest_path)
        manifest.update({k: report[k] for k in ("profile", "mode", "commit", "run_id", "shard_run_ids", "android_api", "android_release", "page_size", "build_fingerprint", "physical_size", "physical_density_dpi", "width_px", "height_px", "density_dpi", "font_scale", "width_dp", "height_dp", "audit_theme")})
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    except (OSError, ValueError, KeyError, TypeError) as exc:
        screenshot_errors.append("Gallery manifest: " + str(exc))
    report.update(missing_screens=missing, screenshot_collection_exit=int(bool(screenshot_errors)),
                  screenshot_observation={"blocking": False, "status": "incomplete" if screenshot_errors else "recorded", "errors": screenshot_errors})
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
