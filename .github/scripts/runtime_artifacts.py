#!/usr/bin/env python3
"""Reuse only direct successful APK runtime producers with identical product inputs."""
import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import zipfile
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/dev"))
from transfer_progress import download
from build_recipe import job_recipe

WORKFLOW = ".github/workflows/prepare-developer-runtime.yml"
INPUTS = (
    "tools/runtime", "android-app/tools/prepare_prebuilt_developer_runtime.py",
    "android-app/tools/developer_tool_launcher.c", "tools/dev/component-bundle.py",
    ".github/scripts/ci-android-sdk.sh",
)
ABI_NAMES = {"arm64-v8a": ("runtime-arm64", "ARM64 工具链产物核验"),
             "x86_64": ("runtime-x86", "x86_64 工具链产物核验")}
LIMIT = 3 * 1024**3

def git(*args):
    return subprocess.check_output(["git", *args], text=True).strip()

def key(source, abi):
    if abi not in ABI_NAMES:
        raise ValueError("Unsupported runtime ABI")
    recipe = job_recipe(git("show", f"{source}:{WORKFLOW}"), "prepare")
    inputs = git("ls-tree", "-r", source, "--", *INPUTS)
    if not inputs:
        raise ValueError("Missing runtime inputs")
    return hashlib.sha256(json.dumps([abi, recipe, inputs], separators=(",", ":")).encode()).hexdigest()

def api(repo, path):
    return json.loads(subprocess.check_output(["gh", "api", f"repos/{repo}/{path}"], text=True))

def timestamp(value):
    return datetime.fromisoformat(value.replace("Z", "+00:00"))

def valid_descriptor(a, name):
    return (a.get("name") == name and a.get("expired") is False
            and type(a.get("id")) is int and a["id"] > 0
            and type(a.get("size_in_bytes")) is int and 0 < a["size_in_bytes"] <= LIMIT
            and re.fullmatch(r"sha256:[0-9a-f]{64}", a.get("digest") or ""))

def direct_producer(repo, artifact, abi, source, expected, api_call=api, fingerprint=key):
    name = f"111-runtime-{abi}-{expected}"
    if not valid_descriptor(artifact, name):
        return None
    rid = (artifact.get("workflow_run") or {}).get("id")
    if type(rid) is not int or rid <= 0:
        return None
    run = api_call(repo, f"actions/runs/{rid}")
    old = run.get("head_sha", "")
    if ((run.get("head_repository") or {}).get("full_name") != repo
        or run.get("path", "").split("@", 1)[0] != ".github/workflows/ci.yml"
        or run.get("event") not in {"pull_request", "push", "workflow_dispatch"}
        or not re.fullmatch(r"[0-9a-f]{40}", old)
        or not isinstance(run.get("run_attempt"), int)):
        return None
    # Same trusted repository and exact product/producer inputs. A squash merge
    # can change commit identity while leaving the actual build inputs identical.
    try:
        if fingerprint(old, abi) != expected:
            return None
    except subprocess.CalledProcessError:
        return None
    attempt = run["run_attempt"]
    response = api_call(repo, f"actions/runs/{rid}/attempts/{attempt}/jobs?per_page=100")
    if response["total_count"] > len(response["jobs"]):
        raise ValueError("Incomplete runtime job index")
    matches = [j for j in response["jobs"]
               if j.get("name", "").rsplit(" / ", 1)[-1] == ABI_NAMES[abi][1]
               and j.get("head_sha") == old and j.get("run_attempt") == attempt
               and j.get("status") == "completed" and j.get("conclusion") == "success"
               and any(s.get("name") == "生成可复用离线组件并回读" and s.get("conclusion") == "success" for s in j.get("steps", []))]
    if len(matches) != 1:
        return None
    j = matches[0]
    if not timestamp(j["started_at"]) <= timestamp(artifact["created_at"]) <= timestamp(j["completed_at"]):
        return None
    return {"artifact_id": artifact["id"], "source": old, "key": expected}

def select(repo, abi, source, expected):
    name = f"111-runtime-{abi}-{expected}"
    for page in range(1, 11):
        response = api(repo, f"actions/artifacts?name={name}&per_page=100&page={page}")
        for a in response["artifacts"]:
            receipt = direct_producer(repo, a, abi, source, expected)
            if receipt:
                return receipt
        if len(response["artifacts"]) < 100:
            break
    return None

def retrieve(repo, abi, artifact_id, expected, source, output):
    name = f"111-runtime-{abi}-{expected}"
    artifact = api(repo, f"actions/artifacts/{artifact_id}")
    if not valid_descriptor(artifact, name) or artifact["id"] != artifact_id:
        raise ValueError("Runtime artifact identity mismatch")
    if key(source, abi) != expected:
        raise ValueError("Runtime source key mismatch")
    output = Path(output)
    output.mkdir(parents=True, exist_ok=False)
    with tempfile.TemporaryDirectory() as tmp:
        archive = Path(tmp) / "runtime.zip"
        download(f"repos/{repo}/actions/artifacts/{artifact_id}/zip", archive, artifact["size_in_bytes"])
        with archive.open("rb") as stream:
            if hashlib.file_digest(stream, "sha256").hexdigest() != artifact["digest"].removeprefix("sha256:"):
                raise ValueError("Runtime GitHub artifact digest mismatch")
        with zipfile.ZipFile(archive) as z:
            entries = z.infolist()
            names = {x.filename for x in entries}
            if (len(names) != len(entries) or "manifest.json" not in names
                or not 2 <= len(entries) <= 101 or sum(x.file_size for x in entries) > LIMIT
                or any(x.is_dir() or not re.fullmatch(r"manifest\.json|payload\.[0-9]{3}", x.filename) for x in entries)):
                raise ValueError("Invalid runtime archive layout")
            z.extractall(output)
    raw = (output / "manifest.json").read_bytes()
    manifest = json.loads(raw)
    if manifest.get("head") != source or manifest.get("component") != ABI_NAMES[abi][0]:
        raise ValueError("Runtime manifest source or ABI mismatch")
    return hashlib.sha256(raw).hexdigest()

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("command", choices=("select", "download"))
    p.add_argument("--abi", required=True, choices=ABI_NAMES)
    p.add_argument("--source", required=True)
    p.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY"))
    p.add_argument("--artifact-id", type=int)
    p.add_argument("--key")
    p.add_argument("--output")
    a = p.parse_args()
    if a.command == "select":
        expected = key(a.source, a.abi)
        receipt = select(a.repo, a.abi, a.source, expected)
        with open(os.environ["GITHUB_OUTPUT"], "a") as stream:
            print("key=" + expected, file=stream)
            print("reused=" + str(bool(receipt)).lower(), file=stream)
            if receipt:
                print("artifact_id=" + str(receipt["artifact_id"]), file=stream)
                print("source=" + receipt["source"], file=stream)
    else:
        print(retrieve(a.repo, a.abi, a.artifact_id, a.key, a.source, a.output))

if __name__ == "__main__":
    main()
