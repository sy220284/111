#!/usr/bin/env python3
"""One final result for actual release, explicit waiting, or no release."""
import json
import os

CHAIN = ("developer-runtime", "host-android", "host-patcher", "release", "device-ready", "device", "publish", "cleanup")
REASONS = {"waiting-ci": "等待完整验收", "waiting-native": "等待原生内核",
           "already-published": "当前源码已发布", "no-client-change": "无客户端发布变更"}

def verdict(jobs, event, ref, eligible):
    if set(jobs) != {"preflight", "check-release", *CHAIN}:
        raise ValueError("发布任务集合不完整")
    results = {key: item["result"] for key, item in jobs.items()}
    if event == "workflow_dispatch":
        if results["preflight"] != "success":
            raise ValueError("手动发布预检未通过")
        eligible = ref == "refs/heads/main"
    elif results["preflight"] != "skipped":
        raise ValueError("自动发布不应执行手动预检")
    if not eligible:
        if any(results[k] != "skipped" for k in ("check-release", *CHAIN)):
            raise ValueError("不适用的发布任务被执行")
        return "当前触发条件无需发布"
    if results["check-release"] != "success":
        raise ValueError("发布候选识别失败")
    decision = jobs["check-release"].get("outputs", {}).get("decision")
    if decision in REASONS:
        if any(results[k] != "skipped" for k in CHAIN):
            raise ValueError("未选定发布时出现额外执行")
        return REASONS[decision]
    if decision != "publish":
        raise ValueError("缺少有效发布决策")
    for key in CHAIN:
        if results[key] != "success":
            raise ValueError(f"正式发布链未完成：{key}={results[key]}")
    return "正式发布与清理均已完成"

if __name__ == "__main__":
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as out:
        try:
            message = verdict(json.loads(os.environ["RESULTS"]), os.environ["EVENT"], os.environ["REF"], os.environ["TRIGGER_ELIGIBLE"] == "true")
        except ValueError as exc:
            print(f"## 发布未完成：{exc}", file=out)
            raise
        print(f"## {message}", file=out)
