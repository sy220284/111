#!/usr/bin/env python3
import json
import os

FULL = {"native", "static-ui", "verify-ui", "ui-emulator", "ui-evidence", "build", "verify-apk", "preflight", "compatibility"}
ALL = FULL | {"release-signing-smoke", "runtime-test", "plan"}

def verify(jobs, event, ref):
    if set(jobs) != ALL:
        raise ValueError(f"验收任务集合变化：missing={ALL-set(jobs)}, unexpected={set(jobs)-ALL}")
    if jobs["plan"]["result"] != "success":
        raise ValueError("调度失败")
    outputs = jobs["plan"]["outputs"]
    if any(outputs.get(k) not in {"true", "false"} for k in ("full", "runtime")):
        raise ValueError("调度凭证不完整")
    full, runtime = (outputs[k] == "true" for k in ("full", "runtime"))
    if runtime and not full:
        raise ValueError("真实内核验收必须包含完整 CI")
    required = set(FULL) if full else set()
    if full and (event == "pull_request" or (event == "workflow_dispatch" and ref != "refs/heads/main")):
        required.add("release-signing-smoke")
    if runtime:
        required.add("runtime-test")
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
        except ValueError as exc:
            print(f"结论：失败 — {exc}", file=out)
            raise
        print("结论：所有适用检查通过", file=out)

if __name__ == "__main__":
    main()
