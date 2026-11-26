#!/usr/bin/env python3
"""Reuse direct successful UI shards only with identical tested APK bytes."""
import argparse
import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import tempfile
import zipfile
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath

from ci_reuse import api, ancestor, delta, affected, artifact_index, MAX_AGE
from device_matrix import PROFILES, MODES, SHARDS, shard_job_name

APKS = ("debug/app-debug.apk","androidTest/debug/app-debug-androidTest.apk")
INPUT = "ui-shard-input.json"
ORIGIN = "ui-shard-origin.json"
FILES = {INPUT,"ui-device-resources.json","ui-instrument-result.log",
         "ui-baseline-diagnosis.json","ui-adaptive-result.log","ui-adaptive-diagnosis.json",
         "ui-motion.log","ui-motion-diagnosis.json","ui-motion-performance.json",
         "ui-motion-environment.json","ui-motion-failure.txt","ui-motion-failure.png",
         "self-update-open.log","self-update-open.json","self-update-after-open.png"}
SCREEN_FOLDERS = {"ui-screenshots","ui-screenshots-adaptive"}
LIMIT = 1024**3


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream,"sha256").hexdigest()


def input_record(root, source, profile, mode, shard):
    return {"source":source,"profile":profile,"mode":mode,"shard":shard,
            "apk":{name:digest(root/"apks"/name) for name in APKS}}


def check_input(record, source, profile, mode, shard):
    if ({k:record.get(k) for k in ("source","profile","mode","shard")}
        != dict(source=source,profile=profile,mode=mode,shard=shard)
        or set(record.get("apk",{})) != set(APKS)
        or not all(re.fullmatch("[0-9a-f]{64}", x) for x in record["apk"].values())):
        raise ValueError("Invalid UI shard input identity")


def same_inputs(previous,current):
    return {k:v for k,v in previous.items() if k!="source"} == {k:v for k,v in current.items() if k!="source"}


def time(s):
    return datetime.fromisoformat(s.replace("Z","+00:00"))


def direct_shard(repo, proof, source, profile, mode, shard, now=None):
    if not proof:
        return None
    rid, attempt, old = proof["run_id"],proof["attempt"],proof["source"]
    if rid == int(os.environ.get("GITHUB_RUN_ID","0")) or not ancestor(old,source):
        return None
    if any("ui" in affected(p) for p in delta(old,source)):
        return None
    run=api(repo,f"actions/runs/{rid}")
    if (run.get("id") != rid or run.get("head_sha") != old or run.get("run_attempt") != attempt
        or (run.get("head_repository") or {}).get("full_name") != repo
        or run.get("path","").split("@",1)[0] != ".github/workflows/ci.yml"
        or run.get("event") not in {"push","pull_request","workflow_dispatch"}
        or not (0 <= ((now or datetime.now(timezone.utc))-time(run["created_at"])).total_seconds() <= MAX_AGE.total_seconds())):
        return None
    response=api(repo,f"actions/runs/{rid}/attempts/{attempt}/jobs?per_page=100")
    if response["total_count"] > len(response["jobs"]):
        raise ValueError("Incomplete UI shard job index")
    jobs=[j for j in response["jobs"]
          if j.get("name") == shard_job_name(profile,mode,shard)
          and j.get("head_sha")==old and j.get("run_attempt")==attempt
          and j.get("status")=="completed" and j.get("conclusion")=="success"
          and any(s.get("name")=="在真实 Android 环境验证侧栏导航并生成首页截图" and s.get("conclusion")=="success"
                  for s in j.get("steps",[]))]
    if len(jobs)!=1:
        return None
    job=jobs[0]
    name=f"ui-shard-{profile}-{mode}-{shard}"
    artifact=artifact_index(repo,rid,time(job["started_at"]),time(job["completed_at"])).get(name)
    if not artifact:
        return None
    return {"run_id":rid,"attempt":attempt,"source":old,"job_id":job["id"],"artifact":artifact}


def extract(archive,folder):
    with zipfile.ZipFile(archive) as z:
        entries=z.infolist()
        if len(entries)>2000 or sum(x.file_size for x in entries)>LIMIT:
            raise ValueError("UI shard ZIP exceeds bounded size")
        seen=set()
        for info in entries:
            p=PurePosixPath(info.filename)
            parts=p.parts
            if (not parts or p.is_absolute() or ".." in parts or "\\" in info.filename
                or stat.S_ISLNK(info.external_attr>>16) or info.filename in seen):
                raise ValueError("Unsafe UI shard ZIP entry")
            seen.add(info.filename)
            if info.is_dir():
                if len(parts)!=1 or parts[0] not in SCREEN_FOLDERS:
                    raise ValueError("Unexpected UI shard directory")
                continue
            if not (len(parts)==1 and parts[0] in FILES
                    or len(parts)==2 and parts[0] in SCREEN_FOLDERS
                    and (parts[1]=="manifest.json" or re.fullmatch("[a-z0-9_-]+-android.png",parts[1]))):
                raise ValueError("Unexpected UI shard file")
            target=folder.joinpath(*parts)
            target.parent.mkdir(parents=True,exist_ok=True)
            with z.open(info) as src,target.open("xb") as dst:
                shutil.copyfileobj(src,dst)


def prepare(repo, proof, source, profile, mode, shard, root):
    current=input_record(root,source,profile,mode,shard)
    (root/INPUT).write_text(json.dumps(current,indent=2)+"\n")
    origin=direct_shard(repo,proof,source,profile,mode,shard)
    if not origin:
        print("No direct successful shard with unchanged inputs; execute device")
        return False
    with tempfile.TemporaryDirectory() as d:
        tmp=Path(d)
        archive=tmp/"artifact.zip"
        with archive.open("wb") as stream:
            subprocess.run(["gh","api",f"repos/{repo}/actions/artifacts/{origin['artifact']['id']}/zip"],
                           stdout=stream,check=True)
        if digest(archive)!=origin["artifact"]["digest"].removeprefix("sha256:"):
            raise ValueError("UI shard GitHub ZIP digest mismatch")
        extracted=tmp/"verified"
        extracted.mkdir()
        extract(archive,extracted)
        if not (extracted/INPUT).is_file():
            print("Historical shard has no APK input proof; execute device")
            return False
        previous=json.loads((extracted/INPUT).read_text())
        check_input(previous,origin["source"],profile,mode,shard)
        if not same_inputs(previous,current):
            print("APK bytes differ from historical shard; execute device")
            return False
        report=json.loads((extracted/f"ui-{mode}-diagnosis.json").read_text())
        if report["commit"]!=origin["source"] or report["status"]!="passed":
            raise ValueError("Historical UI shard report mismatch")
        for path in extracted.iterdir():
            if path.name==INPUT:
                continue
            target=root/path.name
            if target.exists():
                raise ValueError("Existing UI evidence would be overwritten")
            if path.is_dir():
                shutil.copytree(path,target)
            else:
                shutil.copyfile(path,target)
        (root/ORIGIN).write_text(json.dumps(origin|{"input":previous},indent=2)+"\n")
    print(f"Reused original successful UI shard {origin['job_id']} / {origin['artifact']['id']}")
    return True


def verify_origin(repo,proof,source,profile,mode,shard,origin,current):
    previous=origin["input"]
    check_input(previous,origin["source"],profile,mode,shard)
    if not same_inputs(previous,current):
        raise ValueError("UI shard reused different APK bytes")
    actual=direct_shard(repo,proof,source,profile,mode,shard)
    if not actual or actual!={k:v for k,v in origin.items() if k!="input"}:
        raise ValueError("UI shard original success proof changed")
    return origin["source"]


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--repo",required=True)
    p.add_argument("--source",required=True)
    p.add_argument("--profile",required=True,choices=PROFILES)
    p.add_argument("--mode",required=True,choices=MODES)
    p.add_argument("--shard",required=True,choices=SHARDS)
    a=p.parse_args()
    proof=json.loads(os.environ.get("CI_COMPILE_PROOF") or "null")
    reused=prepare(a.repo,proof,a.source,a.profile,a.mode,a.shard,Path.cwd())
    with open(os.environ["GITHUB_OUTPUT"],"a") as stream:
        print("reused="+str(reused).lower(),file=stream)


if __name__=="__main__":
    main()
