#!/usr/bin/env python3
"""Produce and verify Linux x64 auxiliary components. Network is used only by prepare."""
import argparse
import ctypes
import email
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import zipfile

ROOT=pathlib.Path(__file__).resolve().parents[2]
LOCK=json.loads((ROOT/"tools/dev/toolchain-lock.json").read_text())["project"]
UPSTREAM=ROOT/"upstream/codex"
GROUPS=("wheelhouse","emulator","llvm","patcher")

def run(*args,cwd=None,env=None):
    subprocess.run([str(x) for x in args],cwd=cwd,env=dict(os.environ,**(env or {})),check=True)

def output(*args):
    return subprocess.check_output([str(x) for x in args],text=True).strip()

def need(ok,message):
    if not ok: raise ValueError(message)

def digest(path,algorithm="sha256"):
    with path.open("rb") as f:return hashlib.file_digest(f,algorithm).hexdigest()

def download(url,path):
    run("curl","--fail","--location","--retry","3","--retry-all-errors","--proto","=https","--proto-redir","=https",url,"-o",path)

def download_checked(url,path,expected,algorithm="sha256"):
    download(url,path)
    need(digest(path,algorithm)==expected,"digest mismatch: "+path.name)

def verify_python():
    need(".".join(map(str,sys.version_info[:2]))==LOCK["hostPython"],"unexpected host Python version")

def wheelhouse_prepare(stage):
    verify_python()
    requirements=ROOT/"tools/dev/requirements-host.lock"
    need(requirements.is_file(),"missing reviewed Python dependency lock")
    run(sys.executable,"-m","pip","download","--only-binary=:all:","--require-hashes","--dest",stage,"-r",requirements)
    records={}
    for wheel in sorted(stage.glob("*.whl")):
        with zipfile.ZipFile(wheel) as z:
            files=[n for n in z.namelist() if n.endswith(".dist-info/METADATA")]
            need(len(files)==1,"wheel metadata missing")
            meta=email.message_from_bytes(z.read(files[0]))
        name,version=meta["Name"],meta["Version"]
        canonical=re.sub(r"[-_.]+","-",name).lower()
        with urllib.request.urlopen("https://pypi.org/pypi/"+canonical+"/"+version+"/json",timeout=60) as r:
            data=json.load(r)
        actual=digest(wheel)
        matches=[a for a in data["urls"] if a["filename"]==wheel.name and a["digests"]["sha256"]==actual]
        need(len(matches)==1,"wheel does not match PyPI published digest: "+wheel.name)
        need(canonical not in records,"multiple wheels for one dependency")
        records[canonical]=(version,actual)
    need(records["pillow"][0]==LOCK["pillow"] and records["jsonschema"][0]==LOCK["jsonschema"],"direct Python version drift")
    content="".join(f"{n}=={v} --hash=sha256:{h}\n" for n,(v,h) in sorted(records.items()))
    if requirements.is_file():
        need(content==requirements.read_text(),"reviewed wheel lock differs")
    (stage/"requirements.lock").write_text(content)
    print("HOST_WHEEL_LOCK="+json.dumps(records,sort_keys=True),flush=True)
    dots=stage/"dotslash";dots.mkdir()
    source=UPSTREAM/".github/actions/publish-dotslash/requirements.txt"
    shutil.copy2(source,dots/"requirements.txt")
    run(sys.executable,"-m","pip","download","--only-binary=:all:","--require-hashes","--dest",dots,"-r",source)

def wheelhouse_verify(stage):
    verify_python()
    with tempfile.TemporaryDirectory() as d:
        for label,folder,req in (("ci",stage,stage/"requirements.lock"),("dotslash",stage/"dotslash",stage/"dotslash/requirements.txt")):
            v=pathlib.Path(d)/label
            run(sys.executable,"-m","venv",v)
            run(v/"bin/python","-m","pip","install","--no-index","--find-links",folder,"--require-hashes","-r",req)
            run(v/"bin/python","-m","pip","check")
            if label=="ci":
                run(v/"bin/python","-c","import PIL,jsonschema; from importlib.metadata import version; assert version('Pillow')=="+repr(LOCK["pillow"])+"; assert version('jsonschema')=="+repr(LOCK["jsonschema"])+"; jsonschema.validate({'ok':True},{'type':'object'})")

def llvm_prepare(stage):
    # apt's signed repository metadata authenticates the package bytes; keep
    # the exact dpkg package versions as provenance alongside the binary tree.
    run("sudo","apt-get","update")
    if any(not re.search(r"(?m)^\s*Candidate:\s+(?!\(none\))\S+",output("apt-cache","policy",name)) for name in ("clang-21","libclang-21-dev","llvm-21","lld-21")):
        with tempfile.TemporaryDirectory() as d:
            key=pathlib.Path(d)/"llvm.asc"
            download("https://apt.llvm.org/llvm-snapshot.gpg.key",key)
            fingerprints=[l.split(":")[9] for l in output("gpg","--batch","--with-colons","--show-keys",key).splitlines() if l.startswith("fpr:")]
            need(fingerprints and fingerprints[0]=="6084F3CF814B57C1CF12EFD515CF4D18AF4F7421","LLVM signing key mismatch")
            run("sudo","install","-m","0644",key,"/usr/share/keyrings/111-llvm-snapshot.asc")
            run("sudo","bash","-c","printf '%s\\n' 'deb [signed-by=/usr/share/keyrings/111-llvm-snapshot.asc] https://apt.llvm.org/noble/ llvm-toolchain-noble-21 main' > /etc/apt/sources.list.d/111-llvm.list")
            run("sudo","apt-get","update")
    packages=("clang-21","libclang-21-dev","llvm-21","lld-21","ninja-build","pkg-config","libssl-dev")
    run("sudo","apt-get","install","-y","--no-install-recommends",*packages)
    (stage/"packages.tsv").write_text(output("dpkg-query","-W","-f=${Package}\\t${Version}\\n",*packages)+"\n")
    prefix=pathlib.Path("/usr/lib/llvm-21")
    binroot=stage/"bin";lib=stage/"lib";binroot.mkdir();lib.mkdir()
    bins=("clang","clang++","llvm-ar","llvm-nm","llvm-ranlib","llvm-strip","llvm-readelf","llvm-objdump","ld.lld")
    seen={}
    for name in bins:
        source=(prefix/"bin"/name).resolve(strict=True)
        if source in seen:(binroot/name).symlink_to(seen[source])
        else:
            shutil.copy2(source,binroot/name);seen[source]=name
    for name in ("ninja","pkg-config"):
        shutil.copy2(pathlib.Path(shutil.which(name)).resolve(),binroot/name)
    shutil.copytree(prefix/"lib/clang",lib/"clang",symlinks=False)
    source=(prefix/"lib/libclang.so").resolve(strict=True)
    shutil.copy2(source,lib/source.name)
    if source.name!="libclang.so":(lib/"libclang.so").symlink_to(source.name)
    queue=[*seen.keys(),source]
    baseline={"libc.so.6","libm.so.6","libpthread.so.0","libdl.so.2","librt.so.1","ld-linux-x86-64.so.2","libgcc_s.so.1","libstdc++.so.6"}
    visited=set()
    while queue:
        binary=queue.pop()
        if binary in visited:continue
        visited.add(binary)
        for line in output("ldd",binary).splitlines():
            need("not found" not in line,"unresolved LLVM dependency: "+line)
            m=re.match(r"\s*(\S+)\s+=>\s+(/\S+)",line)
            if not m or m.group(1) in baseline:continue
            name,path=m.group(1),pathlib.Path(m.group(2)).resolve(strict=True)
            dest=lib/name
            if dest.exists():need(digest(dest)==digest(path),"conflicting shared library: "+name)
            else:shutil.copy2(path,dest)
            queue.append(path)

def llvm_verify(stage):
    env={"LD_LIBRARY_PATH":str(stage/"lib"),"LIBCLANG_PATH":str(stage/"lib"),"PATH":str(stage/"bin")+os.pathsep+os.environ["PATH"]}
    result=subprocess.check_output([str(stage/"bin/clang"),"--version"],env=dict(os.environ,**env),text=True)
    need(re.search(r"clang version 21\.",result),"LLVM version mismatch")
    with tempfile.TemporaryDirectory() as d:
        src=pathlib.Path(d)/"probe.c";exe=pathlib.Path(d)/"probe"
        src.write_text("int main(void){return 0;}\n")
        run(stage/"bin/clang","-fuse-ld=lld",src,"-o",exe,env=env);run(exe)
    run(sys.executable,"-c","import ctypes,sys; x=ctypes.CDLL(sys.argv[1]); assert x.clang_getClangVersion",stage/"lib/libclang.so",env=env)

def prepare(group,stage):
    stage.mkdir(parents=True,exist_ok=False)
    if group=="wheelhouse":wheelhouse_prepare(stage)
    elif group=="emulator":
        sdk=pathlib.Path(os.environ["ANDROID_HOME"])
        bootstrap=next(sdk.glob("cmdline-tools/*/bin/sdkmanager"))
        # Preinstalled tools can silently write target=android-0 for 37.x images.
        # Install an exact package and use that path, never the first host copy.
        revision=LOCK["deviceCommandLineTools"]
        run(bootstrap,"cmdline-tools;"+revision)
        tools=sdk/"cmdline-tools"/revision
        properties={key.strip():value.strip() for line in (tools/"source.properties").read_text().splitlines()
                    if "=" in line for key,value in [line.split("=",1)]}
        need(properties.get("Pkg.Revision","").strip()==revision,"Android command-line tools revision mismatch")
        manager=tools/"bin/sdkmanager"
        package="system-images;android-"+LOCK["deviceImage"]+";"+LOCK["deviceTarget"]+";x86_64"
        run(manager,"emulator","platform-tools",package,"platforms;android-"+str(LOCK["compileSdk"]))
        for rel in ("emulator","platform-tools","cmdline-tools/"+revision,"licenses","platforms/android-"+str(LOCK["compileSdk"]),
                    package.replace(";","/")):
            src=sdk/rel;dest=stage/rel;dest.parent.mkdir(parents=True,exist_ok=True)
            shutil.copytree(src,dest,symlinks=True)
        print("EMULATOR_REVISION="+(stage/"emulator/source.properties").read_text(),flush=True)
        print("SYSTEM_IMAGE_REVISION="+(stage/package.replace(";","/")/"source.properties").read_text(),flush=True)
        print("COMMAND_LINE_TOOLS_REVISION="+(stage/"cmdline-tools"/revision/"source.properties").read_text(),flush=True)
    elif group=="llvm":llvm_prepare(stage)
    elif group=="patcher":
        for name,sha in (("hdiffpatch_v"+LOCK["hdiffpatch"]+"_sdk_android_hpatchz.zip",LOCK["hdiffpatchAndroidSha256"]),
                         ("hdiffpatch_v"+LOCK["hdiffpatch"]+"_bin_linux64.zip",LOCK["hdiffpatchLinuxSha256"])):
            download_checked("https://github.com/sisong/HDiffPatch/releases/download/v"+LOCK["hdiffpatch"]+"/"+name,stage/name,sha)

def verify(group,stage):
    if group=="wheelhouse":wheelhouse_verify(stage)
    elif group=="llvm":llvm_verify(stage)
    elif group=="emulator":run("bash",ROOT/"tools/build/run-emulator.sh","--probe",env={"CODEX_EMULATOR_SDK":str(stage),"EMULATOR_PROFILE":"mainstream"})
    elif group=="patcher":
        env={"CODEX_PATCHER_HOME":str(stage)}
        run("bash",ROOT/".github/scripts/prepare-android-patcher.sh","arm64-v8a","x86_64",cwd=ROOT,env=env)
        run("bash",ROOT/".github/scripts/create-android-update-fixture.sh",cwd=ROOT,env=env)

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument("command",choices=("prepare","verify"));p.add_argument("group",choices=GROUPS);p.add_argument("directory",type=pathlib.Path);a=p.parse_args()
    (prepare if a.command=="prepare" else verify)(a.group,a.directory.resolve())
if __name__=="__main__":main()
