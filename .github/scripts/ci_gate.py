#!/usr/bin/env python3
import json
import os

from ci_policy import FULL_JOBS as FULL, ALL_JOBS as ALL, required_jobs

def verify(jobs, event, ref):
    if set(jobs) != ALL:
        raise ValueError(f"验收任务集合变化：missing={ALL-set(jobs)}, unexpected={set(jobs)-ALL}")
    if jobs["plan"]["result"] != "success":
        raise ValueError("调度失败")
    outputs = jobs["plan"]["outputs"]
    if any(outputs.get(k) not in {"true", "false"} for k in ("full", "runtime")):
        raise ValueError("调度凭证不完整")
    full, runtime = (outputs[k] == "true" for k in ("full", "runtime"))
    required = required_jobs(full, runtime, event, ref)
    manifest = json.loads(outputs.get("manifest", "{}"))
    if (manifest.get("full") is not full or manifest.get("runtime") is not runtime
            or manifest.get("required") != sorted(required)):
        raise ValueError("验收计划与统一策略不一致")
    for name in ALL - {"plan"}:
        expected = "success" if name in required else "skipped"
        if jobs[name]["result"] != expected:
            raise ValueError(f"{name}: 需要 {expected}，实际 {jobs[name]['result']}")
    return required

def main():
    jobs = json.loads(os.environ["RESULTS"])
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as out:
        print("## 111 统一验收", file=out)
        print("源码 SHA: " + os.environ["SOURCE"], file=out)
        for name, result in jobs.items():
            print(f"- {name}: {result['result']}", file=out)
        try:
            verify(jobs, os.environ["EVENT"], os.environ["REF"])
            manifest = json.loads(jobs["plan"]["outputs"]["manifest"])
            if manifest.get("source") != os.environ["SOURCE"]:
                raise ValueError("验收计划源码 SHA 不一致")
        except ValueError as exc:
            print(f"结论：失败 — {exc}", file=out)
            raise
        print("结论：所有适用检查通过", file=out)

if __name__ == "__main__":
    main()
