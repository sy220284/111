#!/usr/bin/env python3
"""Verified producer/consumer contract shared by developer, CI and release builds."""
import argparse
import hashlib
import json
import os
import pathlib
import re
import subprocess
import tempfile
from urllib.parse import urlencode
from host_components import api, complete_index, download_artifact, install, environment, timestamp, require, JOB_NAMES, GROUPS
from build_recipe import job_recipe

WORKFLOWS = {".github/workflows/dev-toolchain.yml", ".github/workflows/ci.yml",
             ".github/workflows/build-native-codex.yml", ".github/workflows/release.yml"}
# Keep actual production and verification inputs, not download/monitoring consumers.
RECIPE = ("tools/dev/component-bundle.py", "tools/dev/host_components.py",
          "tools/dev/toolchain-lock.json", "tools/dev/run-without-network.sh", "tools/dev/ai-toolchain.py")
HEX = re.compile(r"[0-9a-f]{64}")
SHA = re.compile(r"[0-9a-f]{40}")

def git(*args):
    return subprocess.check_output(["git",*args],text=True).strip()

def key(group, source="HEAD"):
    require(group in GROUPS,"unknown toolchain group")
    paths = [*RECIPE]
    if group == "android":
        paths += ["android-app"]
    elif group == "rust":
        paths += ["upstream/codex/codex-rs", "upstream/codex/.cargo", "android-app/tools/prepare_android_mxc.py"]
    else:
        paths += ["tools/dev/auxiliary.py"]
        if group=="wheelhouse":
            paths += ["tools/dev/requirements-host.lock","android-app/tools/requirements-ui-visual.txt","upstream/codex/.github/actions/publish-dotslash/requirements.txt"]
        if group=="emulator": paths += ["android-app/tools/device-matrix.json","android-app/tools/device_matrix.py","tools/dev/run-emulator.sh",".github/actions/run-emulator"]
        if group=="patcher": paths += [".github/scripts/prepare-android-patcher.sh",".github/scripts/create-android-update-fixture.sh"]
    raw=subprocess.check_output(["git","ls-tree","-r","-z",source,"--",*paths])
    result=[]
    for row in raw.split(b"\0"):
        if not row:
            continue
        meta,path=row.decode().split("\t",1)
        name=pathlib.PurePosixPath(path).name
        common=not path.startswith(("android-app/","upstream/codex/codex-rs/"))
        android=group=="android" and ("/gradle/" in path or name.endswith((".gradle",".gradle.kts")) or
            name in ("gradle.properties","gradle.lockfile","settings.gradle.kts","libs.versions.toml","gradlew","gradlew.bat") or "/buildSrc/" in path)
        rust=group=="rust" and (name in ("Cargo.toml","Cargo.lock","rust-toolchain","rust-toolchain.toml") or
            "/.cargo/" in path or path.endswith("prepare_android_mxc.py"))
        aux=group not in ("android","rust") and (path.startswith("android-app/tools/") or
            (group=="wheelhouse" and path=="upstream/codex/.github/actions/publish-dotslash/requirements.txt"))
        if common and not path.startswith("upstream/codex/") or android or rust or aux:
            result.append([path,meta])
    require(result,"no fingerprint inputs")
    workflow = ".github/workflows/prepare-host-toolchain.yml" if group in ("android", "rust") else ".github/workflows/prepare-aux-toolchain.yml"
    producer = job_recipe(git("show", f"{source}:{workflow}"), group if group in ("android", "rust") else "prepare")
    return hashlib.sha256(json.dumps([group,"linux-x86_64-ubuntu-24.04",producer,sorted(result)],separators=(",",":")).encode()).hexdigest()

def names(group, fingerprint):
    return ["111-toolchain-"+n for n in GROUPS[group]]+["111-toolchain-"+group+"-manifest-pins-"+fingerprint]

def direct_job(run,group):
    attempt=run["run_attempt"]
    index=complete_index(f"actions/runs/{run['id']}/attempts/{attempt}/jobs","jobs",api)
    jobs=[j for j in index if j["name"].rsplit(" / ",1)[-1]==JOB_NAMES[group]
          and j.get("head_sha")==run["head_sha"] and j.get("run_attempt")==attempt
          and j.get("status")=="completed" and j.get("conclusion")=="success"
          and any(s.get("name")=="生成并断网验证独立组件" and s.get("conclusion")=="success" for s in j.get("steps",[]))]
    return jobs[0] if len(jobs)==1 else None

def valid_run(run):
    return (run.get("head_repository") or {}).get("full_name")=="sy220284/111" and run.get("path","").split("@",1)[0] in WORKFLOWS and run.get("event") in ("push","pull_request","workflow_dispatch","workflow_run")

def review(run, group, fingerprint, artifact_ids=None):
    require(valid_run(run),"unexpected host producer repository/workflow")
    job=direct_job(run,group)
    if job is None:
        return None
 
    start,end=timestamp(job["started_at"]),timestamp(job["completed_at"])
    selected={}
    for name in names(group,fingerprint):
        rows=complete_index(f"actions/runs/{run['id']}/artifacts?"+urlencode({"name":name}),"artifacts",api)
        matches=[r for r in rows if r["name"]==name and not r["expired"] and
                 type(r.get("id")) is int and r.get("size_in_bytes",0)>0 and
                 re.fullmatch(r"sha256:[0-9a-f]{64}",r.get("digest") or "") and
                 start<=timestamp(r["created_at"])<=end]
        if len(matches)!=1 or (artifact_ids is not None and matches[0]["id"]!=artifact_ids.get(name)):
            return None
        selected[name]=matches[0]
    return selected

def descriptor(group,source,fingerprint,run_id,rows):
    return {"schema":1,"group":group,"source":source,"key":fingerprint,"run":run_id,
            "ids":{name:row["id"] for name,row in rows.items()}}

def ancestor(old,source):
    if subprocess.run(["git","cat-file","-e",old+"^{commit}"],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode:
        return False
    rc=subprocess.run(["git","merge-base","--is-ancestor",old,source]).returncode
    require(rc in (0,1),"cannot determine producer ancestry")
    return rc==0

def restore(group,source,fingerprint):
    index_name=names(group,fingerprint)[-1]
    current=int(os.environ.get("GITHUB_RUN_ID","0"))
    for page in range(1,11):
        rows=api(f"actions/artifacts?name={index_name}&per_page=100&page={page}")["artifacts"]
        for row in rows:
            rid=(row.get("workflow_run") or {}).get("id")
            if not rid or rid==current or row.get("expired") or row["name"]!=index_name:
                continue
            run=api(f"actions/runs/{rid}")
            old=run.get("head_sha","")
            if not valid_run(run) or not SHA.fullmatch(old) or not ancestor(old,source) or key(group,old)!=fingerprint:
                continue
            accepted=review(run,group,fingerprint)
            if accepted is not None and accepted[index_name]["id"]==row["id"]:
                print(f"复用已断网验收的 {group} 产物：{rid} / {old}",flush=True)
                return descriptor(group,old,fingerprint,rid,accepted)
        if len(rows)<100:
            return None
    raise ValueError("host artifact index exceeds safe scan limit")

def consume(group, record, root, components=None):
    require(record["schema"]==1 and record["group"]==group and SHA.fullmatch(record["source"]) and HEX.fullmatch(record["key"]),"invalid producer descriptor")
    require(record["key"]==key(group),"host build inputs changed")
    require(type(record["run"]) is int and record["run"]>0 and set(record["ids"])==set(names(group,record["key"])),"invalid artifact IDs")
    require(all(type(v) is int and v>0 for v in record["ids"].values()),"invalid artifact ID")
    run=api(f"actions/runs/{record['run']}")
    current=int(os.environ.get("GITHUB_RUN_ID","0"))
    require(run["id"]==record["run"] and (run.get("head_sha")==record["source"] or
            (record["run"]==current and record["source"]==git("rev-parse","HEAD"))),"producer source mismatch")
    rows=review(run,group,record["key"],record["ids"])
    require(rows is not None,"no successful direct disconnected producer or artifact has expired")
    selected_names=tuple(components or GROUPS[group])
    require(set(selected_names)<=set(GROUPS[group]),"invalid requested components")
    root.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(dir=root,prefix=".ci-host-") as tmp:
        directory=pathlib.Path(tmp)
        pin_name=names(group,record["key"])[-1]
        download_artifact(rows[pin_name],directory/("111-toolchain-"+group+"-manifest-pins"))
        for n in selected_names:
            name="111-toolchain-"+n
            download_artifact(rows[name],directory/name)
        install(root,directory,directory,record["source"],selected_names)
    return environment(root,selected_names)

def output(name,value):
    with open(os.environ["GITHUB_OUTPUT"],"a") as stream:
        print(name+"="+value,file=stream)

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("command",choices=("key","restore","emit","consume"))
    p.add_argument("--group",required=True,choices=GROUPS)
    p.add_argument("--source",default="HEAD")
    p.add_argument("--key")
    p.add_argument("--root",type=pathlib.Path)
    p.add_argument("--ids",nargs="*")
    p.add_argument("--components",nargs="+")
    a=p.parse_args()
    source=git("rev-parse",a.source)
    fingerprint=key(a.group,source)
    if a.command=="key":
        print(fingerprint); return
    if a.command=="restore":
        require(a.key==fingerprint,"producer fingerprint changed")
        record=restore(a.group,source,fingerprint)
        output("reused",str(record is not None).lower())
        if record is not None:
            output("descriptor",json.dumps(record,separators=(",",":")))
    elif a.command=="emit":
        require(a.key==fingerprint,"producer fingerprint changed")
        ids=dict((name,int(value)) for name,value in (s.split("=",1) for s in a.ids))
        require(set(ids)==set(names(a.group,fingerprint)) and all(i>0 for i in ids.values()),"incomplete published artifact set")
        output("descriptor",json.dumps({"schema":1,"group":a.group,"source":source,"key":fingerprint,
              "run":int(os.environ["GITHUB_RUN_ID"]),"ids":ids},separators=(",",":")))
    else:
        require(a.root is not None,"missing install root")
        record=json.loads(os.environ["HOST_DESCRIPTOR"])
        env=consume(a.group,record,a.root,a.components)
        with open(os.environ["GITHUB_ENV"],"a") as stream:
            for k,v in env.items():
                if k!="PATH":
                    require("\n" not in v and "\r" not in v,"invalid environment value")
                    print(k+"="+v,file=stream)
        inherited=os.environ.get("PATH","").split(os.pathsep)
        with open(os.environ["GITHUB_PATH"],"a") as stream:
            for value in reversed(env["PATH"].split(os.pathsep)):
                if value not in inherited:
                    print(value,file=stream)
        print(json.dumps({k:v for k,v in env.items() if k!="PATH"},indent=2))

if __name__=="__main__":
    main()
