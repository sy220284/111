"""Install and activate the verified 111 development bundles."""
import importlib.util
import hashlib
import shutil
import zipfile
from datetime import datetime
import json
import os
import pathlib
import re
import subprocess
import tempfile
import time

HERE = pathlib.Path(__file__).resolve().parent
LOCK = json.loads((HERE/"toolchain-lock.json").read_text())
spec = importlib.util.spec_from_file_location("component_bundle", HERE/"component-bundle.py")
bundle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bundle)
ANDROID = ("jdk", "android-sdk", "gradle")
RUST = ("rust", "cargo")
AUX = ("wheelhouse","uv","pnpm","bazel","emulator","llvm","patcher")
GROUPS = {"android":ANDROID,"rust":RUST,**{name:(name,) for name in AUX}}
HOST = ANDROID + RUST + AUX
REPOSITORY = "sy220284/111"
JOB_NAMES = {"android":"111 专用 JDK、Android SDK、Gradle 离线依赖", "rust":"Rust 1.99.1 双 ABI 工具及 Cargo 离线依赖", **{name:"111 辅助组件 · "+name for name in AUX}}
MUTABLE = {"gradle","cargo","uv","pnpm","bazel"}

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

def install(root, directory, pins, head, names, repair=False, sources=None):
    require(re.fullmatch(r"[0-9a-f]{40}",head), "expected producer SHA")
    require(set(names) <= set(HOST) and len(set(names)) == len(names), "invalid component list")
    selection = selected(root)
    validated = {}
    for name in names:
        folder = (sources or {}).get(name, directory/name)
        if not folder.is_dir():
            folder = directory/("111-toolchain-"+name)
        pin_file = pins/(name+".sha256")
        if not pin_file.is_file():
            group = group_for(name)
            pin_file = pins/("111-toolchain-"+group+"-manifest-pins")/(name+".sha256")
        pin = pin_file.read_text().strip()
        require(re.fullmatch(r"[0-9a-f]{64}",pin), "invalid component digest")
        manifest = bundle.load_manifest(folder,name,pin)
        require(manifest["head"] == head, "component source mismatch: "+name)
        validated[name] = (folder, {"head":head,"pin":pin})
    for name,(folder,item) in validated.items():
        dest = path_for(root,name,item)
        # Gradle and Cargo intentionally update their caches. Exact original
        # archive validation and native strict/locked dependency checks remain.
        if name in MUTABLE and not repair and selection.get(name)==item and dest.is_dir() and not dest.is_symlink():
            bundle.load(folder,name,item["pin"])
        else:
            require(bundle.install(name,folder,root/"host",item["pin"])==dest, "unexpected install path")
        receipt = root/"host-manifests"/name/(item["pin"]+".json")
        receipt.parent.mkdir(parents=True,exist_ok=True)
        raw=(folder/"manifest.json").read_bytes()
        require(hashlib.sha256(raw).hexdigest()==item["pin"],"manifest changed before receipt")
        fd, filename=tempfile.mkstemp(prefix=".receipt-",dir=receipt.parent)
        try:
            with os.fdopen(fd,"wb") as stream:
                stream.write(raw)
                stream.flush()
                os.fsync(stream.fileno())
            pathlib.Path(filename).replace(receipt)
        finally:
            pathlib.Path(filename).unlink(missing_ok=True)
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
    for group, members in GROUPS.items():
        require(len({selection[n]["head"] for n in names if n in members})<=1, "do not mix producer commits within "+group)
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
    if "wheelhouse" in paths:
        wheel=paths["wheelhouse"]
        require((wheel/"requirements.lock").is_file() and any(wheel.glob("*.whl")), "wheelhouse incomplete")
        env["CODEX_WHEELHOUSE"]=str(wheel)
    if "uv" in paths:
        uv=paths["uv"]
        require(subprocess.check_output([str(uv/"bin/uv"),"--version"],text=True).split()[1]==p["uv"], "uv version drift")
        require((uv/"cache").is_dir(), "uv cache missing")
        env.update(UV_CACHE_DIR=str(uv/"cache"), UV_OFFLINE="true", UV_PYTHON_DOWNLOADS="never", UV_LINK_MODE="copy")
        search.append(str(uv/"bin"))
    if "pnpm" in paths:
        pnpm=paths["pnpm"]
        package=json.loads((pnpm/"package/package.json").read_text())
        require(package["version"]==p["pnpm"] and (pnpm/"store").is_dir(),"pnpm bundle version/cache drift")
        env.update(npm_config_store_dir=str(pnpm/"store"), npm_config_offline="true", CODEX_PNPM_HOME=str(pnpm))
        search.append(str(pnpm/"bin"))
    if "bazel" in paths:
        bazel=paths["bazel"]
        require(subprocess.check_output([str(bazel/"bin/bazel"),"--version"],text=True).strip()=="bazel "+p["bazel"],"Bazel version drift")
        env["CODEX_BAZEL_HOME"]=str(bazel)
        search.append(str(bazel/"bin"))
    if "emulator" in paths:
        sdk=paths["emulator"]
        image=sdk/"system-images"/("android-"+p["deviceImage"])/p["deviceTarget"]/"x86_64"
        require((image/"package.xml").is_file() and (image/"system.img").is_file() and (sdk/"emulator/emulator").is_file() and (sdk/"platform-tools/adb").is_file(),"emulator image incomplete")
        require("system-images;android-"+p["deviceImage"]+";"+p["deviceTarget"]+";x86_64" in (image/"package.xml").read_text(),"system image identity drift")
        env["CODEX_EMULATOR_SDK"]=str(sdk)
    if "llvm" in paths:
        llvm=paths["llvm"]
        require((llvm/"bin/clang").is_file() and (llvm/"lib/libclang.so").is_file(),"LLVM bundle incomplete")
        env.update(LLVM_HOME=str(llvm),LIBCLANG_PATH=str(llvm/"lib"),LD_LIBRARY_PATH=str(llvm/"lib")+os.pathsep+os.environ.get("LD_LIBRARY_PATH",""))
        search.append(str(llvm/"bin"))
    if "patcher" in paths:
        patcher=paths["patcher"]
        for file, digest in (("hdiffpatch_v"+p["hdiffpatch"]+"_sdk_android_hpatchz.zip",p["hdiffpatchAndroidSha256"]),
                             ("hdiffpatch_v"+p["hdiffpatch"]+"_bin_linux64.zip",p["hdiffpatchLinuxSha256"])):
            require(bundle.digest(patcher/file)==digest,"HDiffPatch archive digest mismatch")
        env["CODEX_PATCHER_HOME"]=str(patcher)
    env["PATH"]=os.pathsep.join(search+[os.environ.get("PATH","")])
    return env

def api(endpoint):
    return json.loads(subprocess.check_output(["gh","api","repos/"+REPOSITORY+"/"+endpoint],text=True))

def complete_index(endpoint, field, request=None):
    """Read a bounded, consistent paginated index; producers may upload concurrently."""
    request=request or api
    separator="&" if "?" in endpoint else "?"
    for attempt in range(4):
        items=[]
        expected=None
        consistent=True
        for page in range(1,11):
            response=request(f"{endpoint}{separator}per_page=100&page={page}")
            total=response["total_count"]
            batch=response[field]
            require(type(total) is int and 0<=total<=1000 and isinstance(batch,list),
                    "invalid or oversized "+field+" index")
            if expected is None:
                expected=total
            if total!=expected or len(batch)!=min(100,max(0,expected-len(items))):
                consistent=False
                break
            items.extend(batch)
            if len(items)==expected:
                ids=[item.get("id") for item in items]
                if all(type(i) is int and i>0 for i in ids) and len(set(ids))==len(ids):
                    return items
                consistent=False
                break
        if not consistent and attempt<3:
            time.sleep(2**attempt)
    raise ValueError("incomplete or unstable "+field+" index")

def timestamp(value):
    return datetime.fromisoformat(value.replace("Z","+00:00"))

def group_for(name):
    return next(group for group,components in GROUPS.items() if name in components)

def reviewed_artifacts(run_id, names):
    require(type(run_id) is int and run_id>0,"invalid producer run ID")
    run=api(f"actions/runs/{run_id}")
    require(run["id"]==run_id and (run.get("head_repository") or {}).get("full_name")==REPOSITORY
            and run["path"].split("@",1)[0] in (".github/workflows/dev-toolchain.yml",".github/workflows/ci.yml",".github/workflows/build-native-codex.yml",".github/workflows/release.yml"),
            "unexpected producer workflow")
    require(run["status"]=="completed","producer run has not finished")
    head=run["head_sha"]
    require(re.fullmatch(r"[0-9a-f]{40}",head),"invalid producer source")
    attempt=run["run_attempt"]
    jobs=complete_index(f"actions/runs/{run_id}/attempts/{attempt}/jobs","jobs")
    groups={group_for(n) for n in names}
    windows={}
    for group in groups:
        rows=[j for j in jobs if j["name"].rsplit(" / ",1)[-1]==JOB_NAMES[group] and j.get("run_attempt")==attempt and j.get("head_sha")==head]
        require(len(rows)==1 and rows[0]["conclusion"]=="success" and rows[0]["status"]=="completed",
                "selected component producer not accepted: "+group)
        windows[group]=(timestamp(rows[0]["started_at"]),timestamp(rows[0]["completed_at"]))
    artifacts=complete_index(f"actions/runs/{run_id}/artifacts","artifacts")
    wanted={"111-toolchain-"+n:group_for(n) for n in names}
    wanted.update({"111-toolchain-"+g+"-manifest-pins":g for g in groups})
    result={}
    for name, group in wanted.items():
        rows=[r for r in artifacts if r["name"]==name or (name.endswith("-manifest-pins") and re.fullmatch(re.escape(name)+r"-[0-9a-f]{64}",r["name"]))]
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
    if not manifest.is_file() or manifest.is_symlink() or not dest.is_dir() or dest.is_symlink():
        return False
    raw=manifest.read_bytes()
    if hashlib.sha256(raw).hexdigest()!=pin:
        return False
    verified=json.loads(raw)
    require(verified["component"]==name and verified["head"]==head and verified["repository"]==REPOSITORY,"receipt identity mismatch")
    if name in MUTABLE:
        return True
    return bundle.verified_tree(dest,verified)

def object_path(root,name,pin):
    require(name in HOST and re.fullmatch(r"[0-9a-f]{64}",pin),"invalid object identity")
    return root/"host-objects"/name/pin

def cache_downloaded_object(root,name,pin,head,downloaded):
    """Keep verified original parts separate from all build-owned writable paths.
    The published manifest pin is the trust root; a local index never grants trust."""
    dest=object_path(root,name,pin)
    manifest=bundle.load(downloaded,name,pin)
    require(manifest["head"]==head,"cached producer mismatch")
    dest.parent.mkdir(parents=True,exist_ok=True)
    require(not dest.exists() and not dest.is_symlink(),"object already exists")
    downloaded.rename(dest)  # same filesystem, publish only after complete verification
    return dest

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
        sources={}
        for name in names:
            pin_file=directory/("111-toolchain-"+group_for(name)+"-manifest-pins")/(name+".sha256")
            pin=pin_file.read_text().strip()
            require(re.fullmatch(r"[0-9a-f]{64}",pin),"invalid published manifest pin")
            if repair or not reusable(root,name,head,pin):
                artifact="111-toolchain-"+name
                cached=object_path(root,name,pin)
                if cached.exists() or cached.is_symlink():
                    require(cached.is_dir() and not cached.is_symlink(),"unsafe cached object")
                    if repair:
                        # A repair uses the intact local original first; corrupted
                        # objects are replaced only using the reviewed producer.
                        try:
                            require(bundle.load(cached,name,pin)["head"]==head,"cached producer mismatch")
                        except (OSError,ValueError,KeyError):
                            shutil.rmtree(cached)
                if not cached.exists():
                    download_artifact(rows[artifact],directory/artifact)
                    cached=cache_downloaded_object(root,name,pin,head,directory/artifact)
                sources[name]=cached
                pending.append(name)
        if pending:
            install(root,directory,directory,head,pending,repair,sources=sources)
    selection=selected(root)
    return {name:str(path_for(root,name,selection[name])) for name in names}


def export_run(run_id, destination, names):
    """Preserve original verified bundles and pins outside expiring Actions storage."""
    require(not destination.exists() and not destination.is_symlink(), "archive destination already exists")
    require(set(names)<=set(HOST) and len(set(names))==len(names), "invalid components")
    head, rows=reviewed_artifacts(run_id,names)
    destination.parent.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".archive-",dir=destination.parent) as tmp:
        stage=pathlib.Path(tmp)/"archive"
        stage.mkdir()
        for name,row in sorted(rows.items()):
            download_artifact(row,stage/name)
        pins={}
        for name in names:
            pin=(stage/("111-toolchain-"+group_for(name)+"-manifest-pins")/(name+".sha256")).read_text().strip()
            manifest=bundle.load(stage/("111-toolchain-"+name),name,pin)
            require(manifest["head"]==head,"archive producer mismatch")
            pins[name]=pin
        record={"schema":1,"repository":REPOSITORY,"run":run_id,"head":head,"components":pins,
                "artifacts":{n:{"id":r["id"],"digest":r["digest"]} for n,r in sorted(rows.items())}}
        (stage/"archive-index.json").write_text(json.dumps(record,indent=2,sort_keys=True)+"\n")
        stage.rename(destination)
    return {"directory":str(destination),"sourceSha":head,"indexSha256":bundle.digest(destination/"archive-index.json"),"components":pins}
