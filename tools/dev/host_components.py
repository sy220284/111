"""Install and activate the verified 111 development bundles."""
import importlib.util
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
