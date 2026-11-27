#!/usr/bin/env python3
"""Release retirement ledger with immutable tag, release and commit identity."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess

TAG=re.compile(r"v(\d+)\.(\d+)\.(\d+)-111\.(\d+)")
def version(tag):
    match=TAG.fullmatch(tag)
    if not match:
        raise ValueError("非法正式版本: "+str(tag))
    return tuple(map(int,match.groups()))

def ledger(path):
    data=json.loads(Path(path).read_text(encoding="utf-8"))
    if data.get("schema")!=1 or not isinstance(data.get("releases"),list):
        raise ValueError("非法退役台账")
    result={}
    ids=set()
    for row in data["releases"]:
        tag=row["tag"]
        version(tag)
        if (tag in result or type(row["release_id"]) is not int or row["release_id"]<=0
            or row["release_id"] in ids or not re.fullmatch(r"[0-9a-f]{40}",row["commit"])
            or not isinstance(row["reason"],str) or not row["reason"].strip()):
            raise ValueError("退役台账身份或理由不完整")
        ids.add(row["release_id"])
        result[tag]=row
    return result

def api(repo,endpoint,*args):
    return subprocess.check_output(["gh","api",f"repos/{repo}/{endpoint}",*args],text=True,timeout=90)

def releases(repo):
    pages=json.loads(api(repo,"releases?per_page=100","--paginate","--slurp"))
    return [row for page in pages for row in page]

def commit(tag):
    return subprocess.check_output(["git","rev-parse","--verify",f"refs/tags/{tag}^{{commit}}"],text=True).strip()

def formal(rows):
    result={}
    for row in rows:
        tag=row.get("tag_name","")
        if not TAG.fullmatch(tag) or row.get("draft") or row.get("prerelease") or not row.get("published_at"):
            continue
        if tag in result:
            raise ValueError("正式发行版身份重复")
        result[tag]=row
    return result

def validate_retired(rows, retired, tag_commit=commit):
    for tag,entry in retired.items():
        if tag_commit(tag)!=entry["commit"]:
            raise ValueError("退役标签提交不一致: "+tag)
        if tag in rows and rows[tag]["id"]!=entry["release_id"]:
            raise ValueError("退役 Release ID 不一致: "+tag)

def selection(rows, retired, source, tag_commit=commit):
    active=formal(rows)
    validate_retired(active,retired,tag_commit)
    if any(entry["commit"]==source for entry in retired.values()):
        raise ValueError("当前候选为已退役源码，须提交修复后重新验收")
    healthy=[tag for tag in active if tag not in retired]
    healthy.sort(key=version,reverse=True)
    # Resolve actual Git tag instead of trusting mutable target_commitish.
    existing=next((tag for tag in healthy if tag_commit(tag)==source),"")
    if active and not healthy:
        raise ValueError("全部已发布版本均被标为退役，缺少可信升级基线")
    return {"existing":existing,"previous_tag":healthy[0] if healthy else ""}

def retirement_plan(rows, retired, tag_commit=commit):
    active=formal(rows)
    validate_retired(active,retired,tag_commit)
    healthy=[tag for tag in active if tag not in retired]
    pending=[tag for tag in active if tag in retired]
    for tag in pending:
        if not any(version(new)>version(tag) for new in healthy):
            raise ValueError("先发布更高的健康版本，再退役: "+tag)
    return [retired[tag] for tag in sorted(pending,key=version)]

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command",choices=["select","retire"])
    parser.add_argument("--ledger",type=Path,default=Path(".github/scripts/retired-releases.json"))
    parser.add_argument("--source")
    parser.add_argument("--apply",action="store_true")
    args=parser.parse_args()
    repo=os.environ["GITHUB_REPOSITORY"]
    retired=ledger(args.ledger)
    rows=releases(repo)
    if args.command=="select":
        if not re.fullmatch(r"[0-9a-f]{40}",args.source or ""):
            raise ValueError("需要完整候选 SHA")
        print(json.dumps(selection(rows,retired,args.source),ensure_ascii=False))
        return
    plan=retirement_plan(rows,retired)
    if args.apply:
        if os.environ.get("GITHUB_REF")!="refs/heads/main":
            raise ValueError("仅允许 main 手动退役")
        for item in plan:
            # Identity and availability of a newer healthy release are rechecked before each delete.
            fresh=retirement_plan(releases(repo),retired)
            if item not in fresh:
                raise ValueError("退役对象已变化，请重新盘点")
            api(repo,f"releases/{item['release_id']}","-X","DELETE")
            print("已退役："+item["tag"])
        if retirement_plan(releases(repo),retired):
            raise ValueError("退役回读仍有待处理版本")
    print(json.dumps({"apply":args.apply,"planned":plan},ensure_ascii=False,indent=2))

if __name__=="__main__":
    main()
