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
GROUPS=("node","wheelhouse","emulator","llvm","patcher")
from portable_host import prepare_node, verify_node, install_python, verify_python
sys.path.insert(0,str(ROOT/"tools/build"))
import native_toolchain
from platform_toolchain import PROFILES, install_android

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

def wheelhouse_prepare(stage):
    python=install_python(stage)
    requirements=ROOT/".github/ci/requirements.lock"
    need(requirements.is_file(),"missing reviewed Python dependency lock")
    run(python,"-m","pip","download","--only-binary=:all:","--require-hashes","--dest",stage,"-r",requirements)
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
    run(python,"-m","pip","download","--only-binary=:all:","--require-hashes","--dest",dots,"-r",source)

def wheelhouse_verify(stage):
    python=verify_python(stage)
    with tempfile.TemporaryDirectory() as d:
        for label,folder,req in (("ci",stage,stage/"requirements.lock"),("dotslash",stage/"dotslash",stage/"dotslash/requirements.txt")):
            v=pathlib.Path(d)/label
            run(python,"-m","venv",v)
            run(v/"bin/python","-m","pip","install","--no-index","--find-links",folder,"--require-hashes","-r",req)
            run(v/"bin/python","-m","pip","check")
            if label=="ci":
                run(v/"bin/python","-c","import PIL,jsonschema; from importlib.metadata import version; assert version('Pillow')=="+repr(LOCK["pillow"])+"; assert version('jsonschema')=="+repr(LOCK["jsonschema"])+"; jsonschema.validate({'ok':True},{'type':'object'})")

def llvm_prepare(stage):
    versions=native_toolchain.install()
    policy=native_toolchain.config()
    (stage/"packages.tsv").write_text("".join(f"{name}\t{version}\n" for name,version in sorted(versions.items())))
    prefix=pathlib.Path("/usr/lib/llvm-"+str(policy["llvmMajor"]))
    binroot=stage/"bin";lib=stage/"lib";binroot.mkdir();lib.mkdir()
    bins=("clang","clang++","llvm-ar","llvm-nm","llvm-ranlib","llvm-strip","llvm-readelf","llvm-objdump","ld.lld")
    seen={}
    for name in bins:
        source=(prefix/"bin"/name).resolve(strict=True)
        if source in seen:(binroot/name).symlink_to(seen[source])
        else:
            shutil.copy2(source,binroot/name);seen[source]=name
    for name,location in policy["executables"].items():
        shutil.copy2(pathlib.Path(location).resolve(strict=True),binroot/name)
        seen[pathlib.Path(location).resolve(strict=True)]=name
    share=stage/"share";share.mkdir()
    cmake_share=sorted(pathlib.Path("/usr/share").glob("cmake-*"))
    need(len(cmake_share)==1,"unexpected CMake data directory")
    shutil.copytree(cmake_share[0],share/cmake_share[0].name)
    shutil.copytree(pathlib.Path("/usr/include/openssl"),stage/"include/openssl")
    for name in ("libssl.so","libcrypto.so","libssl.a","libcrypto.a"):
        shutil.copy2(pathlib.Path("/usr/lib/x86_64-linux-gnu",name).resolve(strict=True),lib/name)
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
    policy=native_toolchain.config()
    need(re.search(r"clang version "+str(policy["llvmMajor"])+r"\.",result),"LLVM version mismatch")
    expected="".join(f"{name}\t{version}\n" for name,version in sorted(policy["packages"].items()))
    need((stage/"packages.tsv").read_text()==expected,"native package receipt drift")
    for name in policy["executables"]:
        run(stage/"bin"/name,"version" if name=="openssl" else "--version",env=env)
    with tempfile.TemporaryDirectory() as d:
        src=pathlib.Path(d)/"probe.c";exe=pathlib.Path(d)/"probe"
        src.write_text("int main(void){return 0;}\n")
        run(stage/"bin/clang","-fuse-ld=lld",src,"-o",exe,env=env);run(exe)
    run(sys.executable,"-c","import ctypes,sys; x=ctypes.CDLL(sys.argv[1]); assert x.clang_getClangVersion",stage/"lib/libclang.so",env=env)

def prepare(group,stage):
    stage.mkdir(parents=True,exist_ok=False)
    if group=="node":prepare_node(stage)
    elif group=="wheelhouse":wheelhouse_prepare(stage)
    elif group=="emulator":
        for package in PROFILES["developer-emulator"]:
            install_android(stage, package)
    elif group=="llvm":llvm_prepare(stage)
    elif group=="patcher":
        for name,sha in (("hdiffpatch_v"+LOCK["hdiffpatch"]+"_sdk_android_hpatchz.zip",LOCK["hdiffpatchAndroidSha256"]),
                         ("hdiffpatch_v"+LOCK["hdiffpatch"]+"_bin_linux64.zip",LOCK["hdiffpatchLinuxSha256"])):
            download_checked("https://github.com/sisong/HDiffPatch/releases/download/v"+LOCK["hdiffpatch"]+"/"+name,stage/name,sha)

def verify(group,stage):
    if group=="node":verify_node(stage)
    elif group=="wheelhouse":wheelhouse_verify(stage)
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
