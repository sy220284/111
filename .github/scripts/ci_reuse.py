#!/usr/bin/env python3
"""Reuse only direct, successful CI groups whose actual inputs remain equal."""
import json
import os
import re
import subprocess
from datetime import datetime, timedelta, timezone
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "android-app/tools"))
from device_matrix import API, RELEASE, MODES, PROFILES, SHARDS, shard_job_name, collect_job_name

GROUPS = {
    "static": {"static-ui"},
    "ui": {"verify-ui", "ui-emulator", "ui-collect", "ui-evidence"},
    "package": {"native", "build", "verify-apk"},
    "preflight": {"preflight"},
    "compat": {"compatibility"},
    "runtime": {"runtime-test"},
    "sign": {"release-signing-smoke"},
}
FULL_GROUPS = set(GROUPS) - {"sign"}
JOB_NAMES = {
    "static": {"协议、视觉规范与静态覆盖检查"},
    "ui": {"Kotlin 编译、协议单元测试与 Android Lint", "四组 UI 用例与证据一致性验收",
           *(shard_job_name(p,m,s) for p in PROFILES for m in MODES for s in SHARDS),
           *(collect_job_name(p,m) for p in PROFILES for m in MODES)},
    "package": {"原生核心统一验收", "build", "ARM64 手机安装包二次核验"},
    "preflight": {"发布脚本回归检查"},
    "compat": {"MXC 和 Code Mode Host 源码适配预演"},
    "runtime": {f"Android {RELEASE} (API {API}) 真实内核及异常恢复验收（x86_64）"},
    "sign": {"PR 内验证 Release 签名构建"},
}
ARTIFACTS = {
    "static": {"codex-android-ui-static-reports"},
    "ui": {"codex-android-ui-check-reports", "codex-android-ui-apks",
           *(f"ui-proof-{p}-{m}" for p in PROFILES for m in MODES),
           *(f"ui-shard-{p}-{m}-{s}" for p in PROFILES for m in MODES for s in SHARDS),
           *(f"codex-android-ui-visual-api{API}-{p}-{m}" for p in PROFILES for m in MODES),
           *(f"codex-android-ui-device-evidence-api{API}-{p}-{m}" for p in PROFILES for m in MODES)},
    "package": {"codex-android-arm64-apk", "codex-android-arm64-git-corresponding-source",
                "codex-android-instrumentation-apk", "codex-android-arm64-apk-audit"},
    "runtime": {"codex-android-real-runtime-evidence"},
}
SCHEMA = 1
MAX_AGE = timedelta(days=2)
# Unknown paths or changed recipes invalidate all groups. The exceptions
# below are limited to known unrelated consumers of a changed file.
RELEASE_ONLY = {
    ".github/scripts/publish-verified-release.sh",
    ".github/scripts/verify-release-device.sh",
    ".github/scripts/verify_device_evidence.py",
    ".github/scripts/generate-release-notes.py",
    ".github/scripts/select_release_candidate.py",
    ".github/scripts/verify_release_acceptance.py",
    ".github/scripts/release_change_gate.py",
}
UI_TEST_TOOLS = {
    "collect_ui_screenshots.py", "ui_evidence_manifest.py", "ui_visual_audit.py",
    "run_ui_device_checks.py", "run_ui_motion_checks.py", "verify_ui_matrix_evidence.py",
    "verify_self_update_open.py", "requirements-ui-visual.txt", "merge_ui_shards.py",
}
SAFE_DOCS = {"README.md", "AGENTS.md", "docs/CI统一交付流程.md"}

def affected(path):
    if path in SAFE_DOCS:
        return set()
    if path in RELEASE_ONLY or (path.startswith(".github/scripts/test_") and path.endswith(".py")):
        return {"preflight"}
    if path.startswith("android-app/app/src/test/"):
        return {"static", "ui", "preflight"}
    if path.startswith("android-app/app/src/androidTest/"):
        return set(GROUPS) - {"compat", "sign"}
    if path.startswith("android-app/app/src/main/") or path.startswith("android-app/app/src/release/"):
        return set(GROUPS) - {"compat"}
    if path.startswith("android-app/tools/") and path.split("/")[-1] in UI_TEST_TOOLS:
        return {"static", "ui", "preflight"}
    return set(GROUPS)

def required_groups(full, runtime, event, ref):
    if not full:
        if runtime:
            raise ValueError("Runtime requires full acceptance")
        return set()
    result = set(FULL_GROUPS)
    if not runtime:
        raise ValueError("Full acceptance requires x86 real runtime")
    if event == "pull_request" or (event == "workflow_dispatch" and ref != "refs/heads/main"):
        result.add("sign")
    return result

def git(*args):
    return subprocess.check_output(["git", *args], text=True).strip()

def tree(sha):
    return git("rev-parse", f"{sha}^{{tree}}")

def ancestor(old, new):
    if subprocess.run(["git", "cat-file", "-e", old+"^{commit}"],
                      stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode:
        return False
    result = subprocess.run(["git", "merge-base", "--is-ancestor", old, new])
    if result.returncode == 1:
        return False
    result.check_returncode()
    return True

def delta(old, new):
    raw = subprocess.check_output(["git","diff","--no-renames","--name-only","-z",old,new,"--"])
    return [x.decode("utf-8") for x in raw.split(b"\0") if x]

def api(repo, endpoint):
    return json.loads(subprocess.check_output(["gh", "api", f"repos/{repo}/{endpoint}"], text=True))

def successful_names(jobs):
    return {x["name"].rsplit(" / ",1)[-1] for x in jobs if x.get("conclusion") == "success"}

def run_names(repo, run_id):
    return successful_names(api(repo, f"actions/runs/{run_id}/jobs?per_page=100")["jobs"])

def artifact_index(repo, run_id):
    rows = api(repo, f"actions/runs/{run_id}/artifacts?per_page=100")["artifacts"]
    return {a["name"]: {"id": a["id"], "digest": a["digest"]}
            for a in rows if not a["expired"] and a.get("size_in_bytes",0)>0
            and re.fullmatch(r"sha256:[0-9a-f]{64}", a.get("digest") or "")}

def candidate(repo, run, target, now):
    old = run.get("head_sha","")
    if (run.get("conclusion") != "success" or run.get("status") != "completed"
        or (run.get("head_repository") or {}).get("full_name") != repo
        or run.get("path","").split("@",1)[0] != ".github/workflows/ci.yml"
        or run.get("event") not in {"pull_request","push","workflow_dispatch"}
        or not re.fullmatch("[0-9a-f]{40}", old)):
        return None
    age = now - datetime.fromisoformat(run["created_at"].replace("Z","+00:00"))
    if not timedelta(0) <= age <= MAX_AGE or not ancestor(old,target):
        return None
    changes = delta(old,target)
    touched = set().union(*(affected(p) for p in changes))
    if touched == set(GROUPS):
        return {}
    names = run_names(repo, run["id"])
    if "111 合并验收" not in names:
        return None
    artifacts = artifact_index(repo, run["id"])
    result = {}
    for group, expected in JOB_NAMES.items():
        required = ARTIFACTS.get(group,set())
        if group not in touched and expected <= names and required <= artifacts.keys():
            result[group] = {"run_id":run["id"], "source":old, "tree":tree(old),
                             "artifacts":{name:artifacts[name] for name in sorted(required)}}
    return result

def make_plan(repo, source, required, force=False):
    reused = {}
    if required and not force:
        runs = api(repo, "actions/workflows/ci.yml/runs?status=success&per_page=50")["workflow_runs"]
        now = datetime.now(timezone.utc)
        for run in runs:
            if run["id"] == int(os.environ.get("GITHUB_RUN_ID","0")):
                continue
            found = candidate(repo, run, source, now)
            if found:
                for group in sorted(required - reused.keys()):
                    if group in found:
                        reused[group] = found[group]
            if required <= reused.keys():
                break
    # Native preparation must execute if the runtime group really executes.
    if "runtime" in required and "runtime" not in reused:
        reused.pop("package",None)
    return {"schema":SCHEMA, "source":source, "tree":tree(source),
            "required":sorted(required), "execute":sorted(required-reused.keys()), "reused":reused}

def check_shape(record, source, required):
    if record["schema"] != SCHEMA or record["source"] != source:
        raise ValueError("CI receipt source/schema mismatch")
    if set(record["required"]) != required or len(record["required"]) != len(required):
        raise ValueError("CI receipt required groups mismatch")
    execute, reused = set(record["execute"]), set(record["reused"])
    if len(record["execute"]) != len(execute) or execute & reused or execute | reused != required:
        raise ValueError("CI receipt groups missing, duplicated or unexpected")
    if "runtime" in execute and "package" not in execute:
        raise ValueError("Actual runtime requires actual native preparation")

def validate(repo, record, source, required, *, now=None):
    check_shape(record,source,required)
    if record["tree"] != tree(source):
        raise ValueError("CI receipt Git tree mismatch")
    now = now or datetime.now(timezone.utc)
    checked = {}
    for group, proof in record["reused"].items():
        rid = proof["run_id"]
        if not isinstance(rid,int) or rid <= 0 or rid == int(os.environ.get("GITHUB_RUN_ID","0")):
            raise ValueError("Invalid or self-referential CI proof")
        if rid not in checked:
            run = api(repo,f"actions/runs/{rid}")
            if run["id"] != rid:
                raise ValueError("CI run ID mismatch")
            checked[rid] = candidate(repo,run,source,now)
        if not checked[rid] or checked[rid].get(group) != proof:
            raise ValueError(f"Missing, expired or changed direct CI evidence: {group}/{rid}")
    return set(record["execute"])

def actual_jobs(groups):
    return set().union(*(GROUPS[g] for g in groups))

def native_key(source):
    from native_index import fingerprint
    from select_native_artifacts import ArtifactSelector
    selector=ArtifactSelector()
    return fingerprint([selector.signature(source,"arm64-v8a"),
                        selector.signature(source,"x86_64"),selector.test_signature(source)])
