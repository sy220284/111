#!/usr/bin/env python3
"""Run native ARM64 Android instrumentation on Google Developer Device Platform."""
import argparse
import hashlib
import json
import re
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CONFIG = json.loads((ROOT / "android-app/tools/device-matrix.json").read_text())
DEVICE = CONFIG["arm64_cloud_device"]
PACKAGE = "io.github.sy220284.codexandroid"
CLASSES = ("CloudArm64DeviceInstrumentedTest", "CoreStartupInstrumentedTest",
           "RuntimeOwnershipInstrumentedTest")


def expected_tests(root=ROOT):
    base = root / "android-app/app/src/androidTest/java" / PACKAGE.replace(".", "/")
    result = set()
    for name in CLASSES:
        names = re.findall(r"@Test\s+fun\s+(\w+)\s*\(", (base / (name + ".kt")).read_text())
        if not names or len(set(names)) != len(names):
            raise ValueError("Missing or duplicate source test methods: " + name)
        result.update((PACKAGE + "." + name, method) for method in names)
    return result


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def command(args):
    return subprocess.check_output(args, text=True).strip()


def session_result(session, sha):
    jobs = session["sessionConfig"]["jobConfigs"]
    reports = session["sessionReport"]["jobReports"]
    if len(jobs) != 1 or len(reports) != 1:
        raise ValueError("Exactly one cloud virtual device is required")
    devices = jobs[0]["allocationConfig"]["deviceConfigs"]
    if len(devices) != 1 or devices[0]["requirement"]["deviceId"] != DEVICE:
        raise ValueError("Wrong cloud ARM64 device")
    if jobs[0].get("labels", {}).get("source_sha") != sha:
        raise ValueError("Cloud result belongs to a different source")
    executions = reports[0]["executionReports"]
    if len(executions) != 1:
        raise ValueError("Unexpected retry or sharded execution")
    for report in (session["sessionReport"], reports[0], executions[0]):
        if report["status"]["statusType"] != "DONE" or report["result"]["resultType"] != "PASSED":
            raise ValueError("Cloud session, job or execution did not pass")
    return executions[0]


def result_tests(xml):
    cases = ET.parse(xml).getroot().iter("testcase")
    seen = set()
    for test in cases:
        key = test.attrib["classname"], test.attrib["name"]
        if key in seen or any(test.find(tag) is not None for tag in ("failure", "error", "skipped")):
            raise ValueError("Duplicate or failed/skipped cloud test: " + str(key))
        seen.add(key)
    if seen != expected_tests():
        raise ValueError("Cloud test identity mismatch: missing=" + str(sorted(expected_tests()-seen))
                         + ", unexpected=" + str(sorted(seen-expected_tests())))
    return seen


def validate_evidence(folder, sha, package_run, signed):
    receipt = json.loads((folder / "receipt.json").read_text())
    session = json.loads((folder / "session.json").read_text())
    session_result(session, sha)
    tests = result_tests(folder / "junit.xml")
    expected = {"source": sha, "package_run": int(package_run), "device": DEVICE,
                "session": session["name"], "signed_candidate_sha256": digest(signed),
                "junit_sha256": digest(folder / "junit.xml"),
                "test_count": len(tests), "status": "passed"}
    if any(receipt.get(k) != v for k, v in expected.items()):
        raise ValueError("Cloud receipt differs from current source, signed candidate or test report")
    for key in ("debug_apk_sha256", "test_apk_sha256"):
        if not re.fullmatch(r"[0-9a-f]{64}", receipt.get(key, "")):
            raise ValueError("Missing cloud APK digest")


def execute(args):
    output = args.output
    output.mkdir(parents=True, exist_ok=True)
    if not re.fullmatch(r"[0-9a-f]{40}", args.sha):
        raise ValueError("Full source SHA required")
    if not all(p.is_file() and p.stat().st_size for p in (args.app, args.test, args.signed)):
        raise ValueError("Missing accepted APK or signed candidate")
    base = ["gcloud", "beta", "device-run", "--project", args.project]
    catalog = command(base + ["devices", "describe", DEVICE, "--format=json"])
    (output / "device.json").write_text(catalog + "\n")
    options = f"cloudArm64=true,cloudApi={CONFIG['api']},cloudPageSize={CONFIG['page_size']}"
    session = command(base + ["sessions", "submit", "instrumentation",
                              "--device", DEVICE, "--target-app", str(args.app),
                              "--test", str(args.test), "--bucket-name", args.bucket,
                              "--additional-test-options", options,
                              "--instrumentation-timeout", "45m", "--labels", "source_sha=" + args.sha,
                              *["--test-targets=class=" + PACKAGE + "." + name for name in CLASSES],
                              "--async", "--format=value(name)"])
    if not re.fullmatch(r"(?:projects/[^/]+/locations/[^/]+/sessions/)?session-[a-zA-Z0-9-]+", session):
        raise ValueError("Cloud provider did not return a valid session identity")
    (output / "session-id.txt").write_text(session + "\n")
    waited = subprocess.run(base + ["sessions", "wait", session], check=False)
    response = json.loads(command(base + ["sessions", "describe", session, "--full", "--format=json"]))
    (output / "session.json").write_text(json.dumps(response, indent=2) + "\n")
    execution = session_result(response, args.sha)
    waited.check_returncode()
    session_id = response["name"].rsplit("/", 1)[-1]
    prefix = f"gs://{args.bucket}/automation/sessions/{session_id}/"
    for filename in ("junit.xml", "instrument.log"):
        paths = [f["gcsOutputFile"]["path"] for f in execution["outputFiles"]
                 if "gcsOutputFile" in f and f["gcsOutputFile"]["path"].endswith("/" + filename)]
        if len(paths) != 1 or not paths[0].startswith(prefix):
            raise ValueError("Missing or mismatched cloud output: " + filename)
        subprocess.run(["gcloud", "storage", "cp", paths[0], str(output / filename)], check=True)
    tests = result_tests(output / "junit.xml")
    receipt = {"source": args.sha, "package_run": args.package_run, "device": DEVICE,
               "session": response["name"], "signed_candidate_sha256": digest(args.signed),
               "debug_apk_sha256": digest(args.app), "test_apk_sha256": digest(args.test),
               "junit_sha256": digest(output / "junit.xml"), "test_count": len(tests), "status": "passed"}
    (output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    validate_evidence(output, args.sha, args.package_run, args.signed)
    print(f"ARM64 native cloud verification passed: {len(tests)} tests, {response['name']}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("run", "verify"))
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--sha", required=True)
    parser.add_argument("--package-run", required=True, type=int)
    parser.add_argument("--signed", required=True, type=Path)
    parser.add_argument("--project")
    parser.add_argument("--bucket")
    parser.add_argument("--app", type=Path)
    parser.add_argument("--test", type=Path)
    args = parser.parse_args()
    if args.mode == "run":
        if not all((args.project, args.bucket, args.app, args.test)):
            parser.error("Cloud project, bucket, app and test are required")
        execute(args)
    else:
        validate_evidence(args.output, args.sha, args.package_run, args.signed)


if __name__ == "__main__":
    main()
