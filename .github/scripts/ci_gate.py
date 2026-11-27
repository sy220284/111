#!/usr/bin/env python3
import json
import os
from pathlib import Path
from ci_reuse import GROUPS, required_groups, actual_jobs, check_shape, validate

FULL = {"native", "runtime-arm64", "runtime-x86", "static-ui", "verify-ui", "ui-emulator", "ui-collect", "ui-evidence", "build", "verify-apk", "preflight", "compatibility", "runtime-test"}
ALL = FULL | {"release-signing-smoke", "plan"}

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
    groups = required_groups(full,runtime,event,ref)
    receipt = json.loads(outputs["receipt"])
    check_shape(receipt,receipt["source"],groups)
    if "compile" in receipt["reused"] and outputs.get("compile_run_id") != str(receipt["reused"]["compile"]["run_id"]):
        raise ValueError("UI APK source does not match the compile receipt")
    if "compile" in receipt["reused"]:
        for artifact, key in (("codex-android-ui-apks","compile_apk_id"), ("ui-apk-integrity","compile_integrity_id")):
            if outputs.get(key) != str(receipt["reused"]["compile"]["artifacts"][artifact]["id"]):
                raise ValueError("UI artifact ID does not match the compile receipt")
    required = actual_jobs(receipt["execute"])
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
            outputs=jobs["plan"]["outputs"]
            receipt=json.loads(outputs["receipt"])
            groups=required_groups(outputs["full"]=="true",outputs["runtime"]=="true",os.environ["EVENT"],os.environ["REF"])
            validate(os.environ["GITHUB_REPOSITORY"],receipt,os.environ["SOURCE"],groups)
            Path("ci-acceptance-receipt.json").write_text(json.dumps(receipt,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
            for group, proof in receipt["reused"].items():
                print(f"- {group}: 复用实际运行 {proof['run_id']} / {proof['source']}",file=out)
        except (ValueError, KeyError, OSError) as exc:
            print(f"结论：失败 — {exc}", file=out)
            raise
        print("结论：所有适用检查通过", file=out)

if __name__ == "__main__":
    main()
