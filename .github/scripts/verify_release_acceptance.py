#!/usr/bin/env python3
"""Require completed device-level jobs, not a green but deliberately skipped APK workflow."""
import argparse
import json
import subprocess
import sys

REQUIRED_JOBS = {
    "build-android.yml": frozenset({
        "build",
        "ARM64 手机安装包二次核验",
    }),
    "check-android-ui.yml": frozenset({
        "Kotlin 编译、协议单元测试与 Android Lint",
        "Android 35 · 360dp UI 交互与截图验收",
        "Android 35 · 393dp UI 交互与截图验收",
        "Android 35 · 411dp UI 交互与截图验收",
    }),
}


def accepted(workflow: str, jobs: list[dict]) -> bool:
    required = REQUIRED_JOBS[workflow]
    completed = {job.get("name") for job in jobs
                 if job.get("conclusion") == "success"}
    return required.issubset(completed)


def gh_json(args: list[str]):
    result = subprocess.run(["gh", *args], capture_output=True, text=True, check=True)
    return json.loads(result.stdout)


def find_acceptance(repo: str, sha: str, workflow: str) -> int | None:
    runs = gh_json([
        "run", "list", "--repo", repo, "--workflow", workflow,
        "--branch", "main", "--commit", sha, "--limit", "100",
        "--json", "databaseId,headSha,conclusion",
    ])
    for run in runs:
        if run.get("conclusion") != "success" or run.get("headSha") != sha:
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
    args = parser.parse_args()
    try:
        for workflow in REQUIRED_JOBS:
            run_id = find_acceptance(args.repo, args.sha, workflow)
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
