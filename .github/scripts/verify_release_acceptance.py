#!/usr/bin/env python3
"""Require completed device-level jobs, not a green but deliberately skipped APK workflow."""
import argparse
import json
import subprocess
import sys

REQUIRED_JOBS = {
    "ci.yml": frozenset({
        "111 合并验收", "build", "ARM64 手机安装包二次核验",
        "Kotlin 编译、协议单元测试与 Android Lint",
        "Android 35 · 360dp UI 交互与截图验收",
        "Android 35 · 393dp UI 交互与截图验收",
        "Android 35 · 411dp UI 交互与截图验收",
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
        if accepted(workflow, response["jobs"]):
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
