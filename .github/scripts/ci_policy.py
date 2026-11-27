#!/usr/bin/env python3
"""Authoritative CI planning and release-input classification."""
import json
import os
import re
import subprocess
import sys
from pathlib import Path

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

FULL_JOBS = frozenset({"native", "verify-ui", "ui-emulator", "build", "verify-apk", "preflight", "compatibility"})
ALL_JOBS = FULL_JOBS | {"release-signing-smoke", "runtime-test", "plan"}

def required_jobs(full, runtime, event, ref):
    if runtime and not full:
        raise ValueError("真实内核验收必须包含完整 CI")
    required = set(FULL_JOBS) if full else set()
    if full and (event == "pull_request" or (event == "workflow_dispatch" and ref != "refs/heads/main")):
        required.add("release-signing-smoke")
    if runtime:
        required.add("runtime-test")
    return required

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
    source = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    if source != os.environ["CI_HEAD"]:
        raise ValueError("实际检出的源码与候选 SHA 不一致")
    manifest = {"source": source, "full": result["full"], "runtime": result["runtime"],
                "required": sorted(required_jobs(result["full"], result["runtime"], os.environ["CI_EVENT"], os.environ["CI_REF"]))}
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as stream:
        for key in ("full", "runtime", "maintenance"):
            print(f"{key}={str(result[key]).lower()}", file=stream)
        print("manifest=" + json.dumps(manifest, ensure_ascii=True, separators=(",", ":")), file=stream)
    print(json.dumps(result, ensure_ascii=False, indent=2))

if __name__ == "__main__":
    main()
