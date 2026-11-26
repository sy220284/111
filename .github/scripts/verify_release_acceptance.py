#!/usr/bin/env python3
"""Require completed device-level jobs, not a green but deliberately skipped APK workflow."""
import argparse
import json
import subprocess
import sys
import tempfile
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'android-app/tools'))
from device_matrix import PROFILES, MODES, job_name
from ci_reuse import FULL_GROUPS, JOB_NAMES, validate, successful_names

REQUIRED_JOBS = {
    "ci.yml": frozenset({
        "111 合并验收", "build", "ARM64 手机安装包二次核验",
        "Kotlin 编译、协议单元测试与 Android Lint",
        "协议、视觉规范与静态覆盖检查", "六组 UI 用例与证据一致性验收",
        *(job_name(profile, mode) for profile in PROFILES for mode in MODES),
        "原生核心统一验收", "发布脚本回归检查", "MXC 和 Code Mode Host 源码适配预演",
    }),
}

def accepted(workflow: str, jobs: list[dict]) -> bool:
    required = REQUIRED_JOBS[workflow]
    completed = {job.get("name", "").rsplit(" / ", 1)[-1] for job in jobs
                 if job.get("conclusion") == "success"}
    return required.issubset(completed)


def gh_json(args: list[str]):
    result = subprocess.run(["gh", *args], capture_output=True, text=True, check=True)
    return json.loads(result.stdout)


def accepted_receipt(repo, run_id, sha, jobs, branch):
    names=successful_names(jobs)
    if "111 合并验收" not in names:
        return False
    artifacts=gh_json(["api",f"repos/{repo}/actions/runs/{run_id}/artifacts?per_page=100"])["artifacts"]
    if not any(a["name"]=="ci-acceptance-receipt" and not a["expired"] for a in artifacts):
        return False
    with tempfile.TemporaryDirectory() as folder:
        subprocess.run(["gh","run","download",str(run_id),"--repo",repo,
                        "--name","ci-acceptance-receipt","--dir",folder],check=True)
        receipt=json.loads((Path(folder)/"ci-acceptance-receipt.json").read_text())
    required=set(receipt["required"])
    if not FULL_GROUPS <= required or required-FULL_GROUPS-{"runtime","sign"}:
        return False
    if branch=="main" and "sign" in required:
        return False
    actual=validate(repo,receipt,sha,required)
    return all(JOB_NAMES[g] <= names for g in actual)

def find_acceptance(repo: str, sha: str, workflow: str, branch: str = "main") -> int | None:
    runs = gh_json([
        "run", "list", "--repo", repo, "--workflow", workflow,
        "--branch", branch, "--commit", sha, "--limit", "100",
        "--json", "databaseId,headSha,conclusion,event,headBranch",
    ])
    for run in runs:
        if run.get("conclusion") != "success" or run.get("headSha") != sha:
            continue
        if branch == "main" and (run.get("event") not in {"push", "workflow_dispatch"} or run.get("headBranch") != "main"):
            continue
        run_id = run["databaseId"]
        response = gh_json(["api", f"repos/{repo}/actions/runs/{run_id}/jobs?per_page=100"])
        if accepted(workflow, response["jobs"]) or accepted_receipt(repo,run_id,sha,response["jobs"],branch):
            return run_id
    return None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--sha", required=True)
    parser.add_argument("--branch", default="main", help="PR 候选分支或默认 main")
    args = parser.parse_args()
    try:
        for workflow in REQUIRED_JOBS:
            run_id = find_acceptance(args.repo, args.sha, workflow, args.branch)
            if run_id is None:
                print(f"提交 {args.sha} 尚未完成 {workflow} 的全部设备级验收")
                return 3
            print(f"验收通过：{workflow}，运行 {run_id}")
    except (subprocess.CalledProcessError, ValueError, KeyError, TypeError) as exc:
        print(f"GitHub CI 验收查询失败：{exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
