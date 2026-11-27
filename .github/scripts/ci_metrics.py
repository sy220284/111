#!/usr/bin/env python3
"""Current attempt's observable resource usage; final gate is still running."""
import datetime as dt
import json
import os
import subprocess

def timestamp(value):
    return dt.datetime.fromisoformat(value.replace("Z", "+00:00"))

def summarize(jobs, created):
    terminal = [j for j in jobs if j.get("status") == "completed"]
    executed = [j for j in terminal if j.get("conclusion") != "skipped" and j.get("started_at") and j.get("completed_at")]
    seconds = lambda j: max(0, (timestamp(j["completed_at"]) - timestamp(j["started_at"])).total_seconds())
    return {
        "completed_jobs": len(terminal),
        "runner_minutes": round(sum(map(seconds, executed)) / 60, 2),
        "elapsed_to_last_completed_minutes": round(max([0] + [(timestamp(j["completed_at"]) - timestamp(created)).total_seconds() for j in executed]) / 60, 2),
        "failed_cancelled_or_timed_out": [j["name"] for j in executed if j["conclusion"] in ("failure", "cancelled", "timed_out")],
        "jobs": [{"name": j["name"], "minutes": round(seconds(j)/60, 2), "conclusion": j["conclusion"]} for j in sorted(executed, key=seconds, reverse=True)],
    }

def api(path):
    return json.loads(subprocess.check_output(["gh", "api", path], text=True, timeout=30))

if __name__ == "__main__":
    repo, run, attempt = (os.environ[k] for k in ("GITHUB_REPOSITORY", "GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT"))
    record = api(f"repos/{repo}/actions/runs/{run}/attempts/{attempt}")
    jobs, page = [], 1
    while True:
        data = api(f"repos/{repo}/actions/runs/{run}/attempts/{attempt}/jobs?per_page=100&page={page}")
        jobs.extend(data["jobs"])
        if len(jobs) >= data["total_count"]:
            break
        if not data["jobs"]:
            raise ValueError("任务分页不完整")
        page += 1
    summary = summarize(jobs, record["run_started_at"])
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as out:
        print("## CI 性能记录（截至门禁执行时）", file=out)
        print("已执行任务累计分钟：" + str(summary["runner_minutes"]), file=out)
        print("截至最后完成任务的耗时：" + str(summary["elapsed_to_last_completed_minutes"]), file=out)
        for job in summary["jobs"]:
            print(f"- {job['name']}: {job['minutes']} 分钟 / {job['conclusion']}", file=out)
    artifacts=[]
    page=1
    while True:
        data=api(f"repos/{repo}/actions/runs/{run}/artifacts?per_page=100&page={page}")
        artifacts.extend(data["artifacts"])
        if len(artifacts)>=data["total_count"]:
            break
        if not data["artifacts"]:
            raise ValueError("产物分页不完整")
        page+=1
    active=[a for a in artifacts if not a.get("expired")]
    summary["artifact_count"]=len(active)
    summary["artifact_bytes"]=sum(a["size_in_bytes"] for a in active)
    with open(os.environ["GITHUB_STEP_SUMMARY"],"a",encoding="utf-8") as out:
        print("当前运行尚存产物（截至采样）：" + str(summary["artifact_count"]) + " 件 / " + str(round(summary["artifact_bytes"]/1024**2,2)) + " MiB",file=out)
    print(json.dumps(summary, ensure_ascii=False))
