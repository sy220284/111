"""Install and activate the verified 111 development bundles."""
import importlib.util
import shutil
import zipfile
from datetime import datetime
import json
import os
import pathlib
import re
import subprocess
import tempfile

HERE = pathlib.Path(__file__).resolve().parent
LOCK = json.loads((HERE/"toolchain-lock.json").read_text())
spec = importlib.util.spec_from_file_location("component_bundle", HERE/"component-bundle.py")
bundle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bundle)
ANDROID = ("jdk", "android-sdk", "gradle")
RUST = ("rust", "cargo")
HOST = ANDROID + RUST
REPOSITORY = "sy220284/111"
JOB_NAMES = {"android":"111 专用 JDK、Android SDK、Gradle 离线依赖", "rust":"Rust 1.99.1 双 ABI 工具及 Cargo 离线依赖"}

def require(value, message):
    if not value:
        raise ValueError(message)

def selected(root):
    path = root/"host-selection.json"
    if not path.exists():
        return {}
    require(path.is_file() and not path.is_symlink(), "invalid host selection file")
    result = json.loads(path.read_text())
    require(isinstance(result, dict) and set(result) <= set(HOST), "invalid host selection")
    for name, item in result.items():
        require(re.fullmatch(r"[0-9a-f]{40}",item["head"]) and re.fullmatch(r"[0-9a-f]{64}",item["pin"]), "invalid host pin: "+name)
    return result

def path_for(root, name, item):
    return root/"host"/name/item["pin"]

def install(root, directory, pins, head, names, repair=False):
    require(re.fullmatch(r"[0-9a-f]{40}",head), "expected producer SHA")
    require(set(names) <= set(HOST) and len(set(names)) == len(names), "invalid component list")
    selection = selected(root)
    validated = {}
    for name in names:
        folder = directory/name
        if not folder.is_dir():
            folder = directory/("111-toolchain-"+name)
        pin_file = pins/(name+".sha256")
        if not pin_file.is_file():
            group = "android" if name in ANDROID else "rust"
            pin_file = pins/("111-toolchain-"+group+"-manifest-pins")/(name+".sha256")
        pin = pin_file.read_text().strip()
        require(re.fullmatch(r"[0-9a-f]{64}",pin), "invalid component digest")
        manifest = bundle.load(folder,name,pin)
        require(manifest["head"] == head, "component source mismatch: "+name)
        validated[name] = (folder, {"head":head,"pin":pin})
    for name,(folder,item) in validated.items():
        dest = path_for(root,name,item)
        # Gradle and Cargo intentionally update their caches. Exact original
        # archive validation and native strict/locked dependency checks remain.
        if not (name in ("gradle","cargo") and not repair and selection.get(name)==item and dest.is_dir() and not dest.is_symlink()):
            require(bundle.install(name,folder,root/"host",item["pin"])==dest, "unexpected install path")
        receipt = root/"host-manifests"/name/(item["pin"]+".json")
        receipt.parent.mkdir(parents=True,exist_ok=True)
        receipt.write_bytes((folder/"manifest.json").read_bytes())
        selection[name] = item
    root.mkdir(parents=True,exist_ok=True)
    fd, filename = tempfile.mkstemp(prefix=".host-selection-",dir=root)
    path = pathlib.Path(filename)
    try:
        with os.fdopen(fd,"w") as stream:
            json.dump(selection,stream,indent=2,sort_keys=True)
            stream.write("\n")
        path.replace(root/"host-selection.json")
    finally:
        path.unlink(missing_ok=True)
    return {name:str(path_for(root,name,selection[name])) for name in names}

def environment(root,names):
    selection = selected(root)
    require(set(names)<=selection.keys(), "missing 111 components: "+", ".join(sorted(set(names)-selection.keys())))
    require(len({selection[n]["head"] for n in names})==1, "do not mix producer commits")
    paths={n:path_for(root,n,selection[n]) for n in names}
    require(all(p.is_dir() and not p.is_symlink() for p in paths.values()), "component directory missing")
    p=LOCK["project"]
    env={}
    search=[]
    if "jdk" in paths:
        java=paths["jdk"]/"bin/java"
        version=subprocess.check_output([str(java),"-version"],stderr=subprocess.STDOUT,text=True)
        require(re.search(r'version "'+p["jdk"]+r'(?:[."]|$)',version),"JDK version drift")
        env["JAVA_HOME"]=str(paths["jdk"])
        search.append(str(java.parent))
    if "android-sdk" in paths:
        sdk=paths["android-sdk"]
        ndk=sdk/"ndk"/p["ndk"]
        for relative in ("platforms/android-"+str(p["compileSdk"])+"/android.jar","build-tools/"+p["buildTools"]+"/aapt","platform-tools/adb"):
            require((sdk/relative).is_file(),"SDK component missing: "+relative)
        require(re.search(r"^Pkg\.Revision\s*=\s*"+re.escape(p["ndk"])+r"\s*$",(ndk/"source.properties").read_text(),re.M),"NDK version drift")
        env.update(ANDROID_HOME=str(sdk),ANDROID_SDK_ROOT=str(sdk),ANDROID_NDK_HOME=str(ndk))
        search.append(str(sdk/"platform-tools"))
    if "gradle" in paths:
        gradle=paths["gradle"]
        require((gradle/"wrapper/dists").is_dir() and (gradle/"caches/modules-2").is_dir(),"Gradle cache incomplete")
        env["GRADLE_USER_HOME"]=str(gradle)
    if "rust" in paths:
        rust=paths["rust"]
        binary=rust/"bin/rustc"
        version=subprocess.check_output([str(binary),"--version"],text=True)
        require(version.split()[1]==p["rust"],"Rust version drift")
        for target in p["rustTargets"]:
            require((rust/"lib/rustlib"/target/"lib").is_dir(),"Rust target missing: "+target)
        env.update(RUSTC=str(binary),RUSTDOC=str(rust/"bin/rustdoc"))
        search.append(str(rust/"bin"))
    if "cargo" in paths:
        cargo=paths["cargo"]
        require((cargo/"git/db").is_dir() and (cargo/"registry").is_dir(),"Cargo cache incomplete")
        env.update(CARGO_HOME=str(cargo),CARGO_NET_OFFLINE="true")
    env["PATH"]=os.pathsep.join(search+[os.environ.get("PATH","")])
    return env

def api(endpoint):
    return json.loads(subprocess.check_output(["gh","api","repos/"+REPOSITORY+"/"+endpoint],text=True))

def timestamp(value):
    return datetime.fromisoformat(value.replace("Z","+00:00"))

def group_for(name):
    return "android" if name in ANDROID else "rust"

def reviewed_artifacts(run_id, names):
    require(type(run_id) is int and run_id>0,"invalid producer run ID")
    run=api(f"actions/runs/{run_id}")
    require(run["id"]==run_id and (run.get("head_repository") or {}).get("full_name")==REPOSITORY
            and run["path"].split("@",1)[0]==".github/workflows/dev-toolchain.yml",
            "unexpected producer workflow")
    require(run["status"]=="completed","producer run has not finished")
    head=run["head_sha"]
    require(re.fullmatch(r"[0-9a-f]{40}",head),"invalid producer source")
    attempt=run["run_attempt"]
    jobs=api(f"actions/runs/{run_id}/attempts/{attempt}/jobs?per_page=100")
    require(jobs["total_count"]==len(jobs["jobs"]),"incomplete job index")
    groups={group_for(n) for n in names}
    windows={}
    for group in groups:
        rows=[j for j in jobs["jobs"] if j["name"]==JOB_NAMES[group] and j.get("run_attempt")==attempt and j.get("head_sha")==head]
        require(len(rows)==1 and rows[0]["conclusion"]=="success" and rows[0]["status"]=="completed",
                "selected component producer not accepted: "+group)
        windows[group]=(timestamp(rows[0]["started_at"]),timestamp(rows[0]["completed_at"]))
    response=api(f"actions/runs/{run_id}/artifacts?per_page=100")
    require(response["total_count"]==len(response["artifacts"]),"incomplete artifact index")
    wanted={"111-toolchain-"+n:group_for(n) for n in names}
    wanted.update({"111-toolchain-"+g+"-manifest-pins":g for g in groups})
    result={}
    for name, group in wanted.items():
        rows=[r for r in response["artifacts"] if r["name"]==name]
        require(len(rows)==1,"missing or ambiguous artifact: "+name)
        row=rows[0]
        start,end=windows[group]
        require(not row["expired"] and type(row["id"]) is int and row["size_in_bytes"]>0 and
                re.fullmatch(r"sha256:[0-9a-f]{64}",row.get("digest") or "") and
                start<=timestamp(row["created_at"])<=end,
                "artifact is expired, unverified or from another attempt: "+name)
        result[name]=row
    return head,result

def download_artifact(row,destination):
    destination.mkdir(parents=True,exist_ok=False)
    archive=destination.parent/(destination.name+".zip")
    try:
        with archive.open("wb") as output:
            subprocess.run(["gh","api",f"repos/{REPOSITORY}/actions/artifacts/{row['id']}/zip"],stdout=output,check=True)
        require(archive.stat().st_size==row["size_in_bytes"] and bundle.digest(archive)==row["digest"][7:],
                "artifact ZIP digest mismatch: "+row["name"])
        with zipfile.ZipFile(archive) as zipped:
            files=zipped.infolist()
            require(len(files)<=105 and sum(i.file_size for i in files)<=32*1024**3,"artifact exceeds bounds")
            seen=set()
            for info in files:
                name=info.filename
                require(re.fullmatch(r"(?:manifest\.json|payload\.\d{3}|[a-z-]+\.sha256|baseline-status)",name)
                        and not info.is_dir() and name not in seen,"unexpected artifact entry: "+name)
                seen.add(name)
                with zipped.open(info) as source,(destination/name).open("wb") as target:
                    shutil.copyfileobj(source,target,1024**2)
    finally:
        archive.unlink(missing_ok=True)

def reusable(root,name,head,pin):
    item=selected(root).get(name)
    if item!={"head":head,"pin":pin}:
        return False
    manifest=root/"host-manifests"/name/(pin+".json")
    dest=path_for(root,name,item)
    if not manifest.is_file() or bundle.digest(manifest)!=pin or not dest.is_dir() or dest.is_symlink():
        return False
    if name in ("gradle","cargo"):
        return True
    return bundle.manifest_digest(bundle.inventory(dest))==json.loads(manifest.read_text())["treeSha256"]

def install_run(root,run_id,names,repair=False):
    require(set(names)<=set(HOST) and len(set(names))==len(names),"invalid components")
    head,rows=reviewed_artifacts(run_id,names)
    root.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".host-download-",dir=root) as tmp:
        directory=pathlib.Path(tmp)
        for group in sorted({group_for(n) for n in names}):
            name="111-toolchain-"+group+"-manifest-pins"
            download_artifact(rows[name],directory/name)
        pending=[]
        for name in names:
            pin_file=directory/("111-toolchain-"+group_for(name)+"-manifest-pins")/(name+".sha256")
            pin=pin_file.read_text().strip()
            require(re.fullmatch(r"[0-9a-f]{64}",pin),"invalid published manifest pin")
            if repair or not reusable(root,name,head,pin):
                artifact="111-toolchain-"+name
                download_artifact(rows[artifact],directory/artifact)
                pending.append(name)
        if pending:
            install(root,directory,directory,head,pending,repair)
    selection=selected(root)
    return {name:str(path_for(root,name,selection[name])) for name in names}
