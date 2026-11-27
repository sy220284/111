#!/usr/bin/env python3
"""Select original successful CI compilation, never a merge acceptance receipt."""
from datetime import datetime, timezone
import os
from ci_reuse import api, candidate

def select(repo, run_id, source):
    if not run_id.isdecimal():
        raise ValueError("必须提供原始 CI 运行编号")
    run=api(repo, f"actions/runs/{run_id}")
    proof=(candidate(repo, run, source, datetime.now(timezone.utc)) or {}).get("compile")
    if not proof:
        raise ValueError("该运行缺少同源、有效、实际成功的编译凭证；请选最近七天的原始成功编译")
    return proof

def main():
    proof=select(os.environ["GITHUB_REPOSITORY"], os.environ["SOURCE_RUN"], os.environ["GITHUB_SHA"])
    with open(os.environ["GITHUB_OUTPUT"],"a",encoding="utf-8") as out:
        print("apk_id="+str(proof["artifacts"]["codex-android-ui-apks"]["id"]),file=out)
        print("integrity_id="+str(proof["artifacts"]["ui-apk-integrity"]["id"]),file=out)
        print("source="+proof["source"],file=out)
    print("复用原始编译："+str(proof["run_id"])+" / "+proof["source"])

if __name__=="__main__":
    main()
