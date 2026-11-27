#!/usr/bin/env python3
"""Inventory Actions storage and prune only independent observations after review."""
import argparse
from collections import defaultdict
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import re
import subprocess

OBSERVATION = re.compile(r"codex-android-ui-visual-api\d+-(?:mainstream|ultra)-(?:baseline|adaptive)|ui-diagnostic-(?:navigation|subpages|components|overlays|engine|video-attachment|installer)-(?:mainstream|ultra)-(?:baseline|adaptive)")
FINGERPRINT = re.compile(r"(?:111-runtime-(?:arm64-v8a|x86_64)-|v8-(?:binary|recovery)-|native-index-(?:arm64-v8a|x86_64)-|v8-source-evidence-(?:arm64-v8a|x86_64)-|provider-index-|android-delivery-executor-linux-x86_64-)[0-9a-f]{64}")
GRACE = timedelta(days=7)
KEEP = 3

def api(repo, endpoint, *args):
    return subprocess.check_output(["gh","api",f"repos/{repo}/{endpoint}",*args],text=True,timeout=90)

def listing(repo):
    pages=json.loads(api(repo,"actions/artifacts?per_page=100","--paginate","--slurp"))
    rows=[row for page in pages for row in page["artifacts"]]
    if not pages or len({row["id"] for row in rows})!=len(rows) or any(page["total_count"]!=len(rows) for page in pages):
        raise ValueError("产物分页变化或不完整，停止治理，重新生成清单")
    return rows

def stamp(value):
    return datetime.fromisoformat(value.replace("Z","+00:00"))

def run_id(a):
    return (a.get("workflow_run") or {}).get("id")

def family(name):
    if OBSERVATION.fullmatch(name):
        return "observation"
    if FINGERPRINT.fullmatch(name):
        return "fingerprinted"
    if name.startswith(("codex-android-native","native-","111-runtime-","v8-","111-toolchain-","android-delivery-executor-","provider-index-")):
        return "build-protected"
    if name.startswith(("release-","codex-android-arm64","codex-android-instrumentation","codex-android-ui","ui-proof-","ui-shard-","ui-apk-integrity","codex-android-real-runtime","ci-acceptance")):
        return "acceptance-protected"
    return "unknown-protected"

def inventory(rows, runs=None, now=None):
    now=now or datetime.now(timezone.utc)
    runs=runs or {}
    families=defaultdict(lambda:{"count":0,"bytes":0})
    identical=defaultdict(list)
    observations=defaultdict(list)
    fingerprint_sets=defaultdict(list)
    expired=0
    for a in rows:
        if a.get("expired") is True:
            expired+=1
            continue
        if type(a["id"]) is not int or type(a["size_in_bytes"]) is not int or a["size_in_bytes"]<0:
            raise ValueError("非法产物元数据")
        group=family(a["name"])
        families[group]["count"]+=1
        families[group]["bytes"]+=a["size_in_bytes"]
        digest=a.get("digest") or ""
        if re.fullmatch(r"sha256:[0-9a-f]{64}",digest):
            identical[(a["name"],digest,a["size_in_bytes"])].append(a["id"])
        if group=="fingerprinted":
            fingerprint_sets[a["name"]].append(a)
        if group=="observation":
            observations[a["name"]].append(a)
    candidates=[]
    for name,items in observations.items():
        # Keep newest three records regardless of run result; failed/incomplete evidence is never deleted here.
        for a in sorted(items,key=lambda x:(stamp(x["created_at"]),x["id"]),reverse=True)[KEEP:]:
            run=runs.get(run_id(a),{})
            if now-stamp(a["created_at"])<GRACE or run.get("status")!="completed" or run.get("conclusion")!="success":
                continue
            if not re.fullmatch(r"sha256:[0-9a-f]{64}",a.get("digest") or ""):
                continue
            candidates.append({k:a[k] for k in ("id","name","size_in_bytes","created_at","digest")} | {"run_id":run_id(a)})
    return {"families":dict(families),"active_bytes":sum(x["bytes"] for x in families.values()),
            "expired_count":expired,
            "exact_duplicates":[{"name":k[0],"digest":k[1],"ids":sorted(ids),"duplicate_bytes":k[2]*(len(ids)-1)} for k,ids in sorted(identical.items()) if len(ids)>1],
            "repeated_fingerprints":[{"name":name,"ids":sorted(a["id"] for a in items),"bytes":sum(a["size_in_bytes"] for a in items)} for name,items in sorted(fingerprint_sets.items()) if len(items)>1],
            "observation_cleanup":sorted(candidates,key=lambda a:a["id"]),
            "observation_cleanup_bytes":sum(a["size_in_bytes"] for a in candidates)}

def collect(repo):
    rows=listing(repo)
    # Query job status only for old records outside the retained three per series.
    # Hundreds of recent screenshot uploads do not require hundreds of API calls.
    runs={}
    observations=defaultdict(list)
    now=datetime.now(timezone.utc)
    for a in rows:
        if not a.get("expired") and OBSERVATION.fullmatch(a["name"]):
            observations[a["name"]].append(a)
    for items in observations.values():
        for a in sorted(items,key=lambda x:(stamp(x["created_at"]),x["id"]),reverse=True)[KEEP:]:
            rid=run_id(a)
            if now-stamp(a["created_at"])>=GRACE and rid and rid not in runs:
                runs[rid]=json.loads(api(repo,f"actions/runs/{rid}"))
    return inventory(rows,runs,now)

def apply(repo, plan):
    for item in plan["observation_cleanup"]:
        # Re-read immediately before deleting. Never delete anything consumed by acceptance, native or release.
        current=json.loads(api(repo,f"actions/artifacts/{item['id']}"))
        if (not OBSERVATION.fullmatch(current["name"]) or current.get("expired") is not False
            or any(current[k]!=item[k] for k in ("id","name","size_in_bytes","created_at","digest"))
            or run_id(current)!=item["run_id"]):
            raise ValueError("产物身份变化，停止清理")
        run=json.loads(api(repo,f"actions/runs/{item['run_id']}"))
        if run.get("status")!="completed" or run.get("conclusion")!="success":
            raise ValueError("原始作业状态变化，停止清理")
        api(repo,f"actions/artifacts/{item['id']}","-X","DELETE")
        print(f"已删除独立观察产物 {item['id']} / {item['name']}",flush=True)
    after=listing(repo)
    remaining={a["id"] for a in after}
    if any(x["id"] in remaining for x in plan["observation_cleanup"]):
        raise ValueError("删除回读仍发现目标产物")
    return len(plan["observation_cleanup"])

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--apply",action="store_true")
    p.add_argument("--output",type=Path,default=Path("artifact-inventory.json"))
    args=p.parse_args()
    repo=os.environ["GITHUB_REPOSITORY"]
    if args.apply and os.environ.get("GITHUB_REF")!="refs/heads/main":
        raise ValueError("仅允许 main 手动治理")
    plan=collect(repo)
    args.output.write_text(json.dumps(plan,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    if args.apply:
        plan["deleted_count"]=apply(repo,plan)
        args.output.write_text(json.dumps(plan,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    with open(os.environ["GITHUB_STEP_SUMMARY"],"a",encoding="utf-8") as out:
        print("## Actions 产物盘点",file=out)
        for group,row in plan["families"].items():
            print(f"- {group}: {row['count']} 件 / {row['bytes']/1024**2:.1f} MiB",file=out)
        print(f"- 同名同摘要重复组：{len(plan['exact_duplicates'])}；重复指纹组：{len(plan['repeated_fingerprints'])}",file=out)
        print(f"- 可清理独立观察证据：{len(plan['observation_cleanup'])} 件 / {plan['observation_cleanup_bytes']/1024**2:.1f} MiB",file=out)
        print("- 原生、运行时、验收及发布证据保留原 ID，重复仅列入治理报告。",file=out)
        print("- 已删除："+str(plan.get("deleted_count",0)),file=out)

if __name__=="__main__":
    main()
