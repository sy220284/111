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
import tomllib
import urllib.request
import urllib.parse
import zipfile

ROOT=pathlib.Path(__file__).resolve().parents[2]
LOCK=json.loads((ROOT/"tools/dev/toolchain-lock.json").read_text())["project"]
UPSTREAM=ROOT/"upstream/codex"
GROUPS=("wheelhouse","uv","pnpm","bazel","emulator","llvm","patcher")
UV_PROJECTS=(".github/actions/setup-python-release-tools","scripts","scripts/codex_package/smoke_tests","sdk/python","sdk/python-runtime")
BAZEL_TARGETS=("//codex-rs/app-server:codex-app-server","//codex-rs/code-mode-host:codex-code-mode-host","//tools/argument-comment-lint:argument-comment-lint-driver")

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

def uv_sync(stage,offline):
    verify_python()
    executable=stage/"bin/uv"
    need(output(executable,"--version").split()[1]==LOCK["uv"],"uv version mismatch")
    with tempfile.TemporaryDirectory() as d:
        for i,project in enumerate(UV_PROJECTS):
            env={"UV_CACHE_DIR":str(stage/"cache"),"UV_PROJECT_ENVIRONMENT":str(pathlib.Path(d)/str(i)),
                 "UV_LINK_MODE":"copy","UV_NO_PROGRESS":"1","UV_PYTHON_DOWNLOADS":"never"}
            args=["sync","--project",UPSTREAM/project,"--locked","--all-groups","--no-install-project","--no-build","--no-python-downloads","--python",sys.executable]
            if offline: args.append("--offline")
            run(executable,*args,env=env)
            run(executable,"pip","check","--python",pathlib.Path(d)/str(i)/"bin/python",env=env)

def remove_pnpm_project_index(store):
    """pnpm's project registry links identify a checkout, not package content."""
    for version in sorted(store.glob("v[0-9]*")):
        need(version.is_dir() and not version.is_symlink(), "invalid pnpm store version")
        index=version/"projects"
        if not index.exists() and not index.is_symlink():
            continue
        need(index.is_dir() and not index.is_symlink(), "invalid pnpm project index")
        # The entire registry is regenerated by pnpm install at its destination.
        # Never dereference its links, and never remove package files/indexes.
        shutil.rmtree(index)

def pnpm_install(stage,offline):
    cmd=stage/"bin/pnpm"
    need(output(cmd,"--version")==LOCK["pnpm"],"pnpm version mismatch")
    env={"npm_config_store_dir":str(stage/"store"),"CI":"true","PNPM_DISABLE_SELF_UPDATE_CHECK":"1",
         "PATH":str(stage/"bin")+os.pathsep+os.environ["PATH"]}
    if not offline:
        run(cmd,"fetch","--frozen-lockfile","--store-dir",stage/"store",cwd=UPSTREAM,env=env)
        # fetch's virtual store is local to the source checkout, not the portable store.
        for p in (UPSTREAM/"node_modules",UPSTREAM/"codex-cli/node_modules",UPSTREAM/"codex-rs/responses-api-proxy/npm/node_modules",UPSTREAM/"sdk/typescript/node_modules"):
            if p.exists(): shutil.rmtree(p)
        remove_pnpm_project_index(stage/"store")
    else:
        run(cmd,"install","--offline","--frozen-lockfile","--store-dir",stage/"store",cwd=UPSTREAM,env=env)
        run(cmd,"exec","prettier","--version",cwd=UPSTREAM,env=env)

BAZEL_REGISTRY_REPO="rules_rs++crate+crates_https___index.crates.io_"
BAZEL_REGISTRY_REPOS=(BAZEL_REGISTRY_REPO,"rules_rs++crate+argument_comment_lint_crates_https___index.crates.io_","rules_rs++crate+rrprd_https___index.crates.io_")
BAZEL_REGISTRY_NAME=re.compile(r"rules_rs\+\+crate\+[A-Za-z0-9_]+_https___index\.crates\.io_")

def bazel_registry_names(external):
    """Discover all generated crates.io registry config repositories from the actual fetch."""
    names=sorted(p.name for p in external.iterdir() if BAZEL_REGISTRY_NAME.fullmatch(p.name))
    need(set(BAZEL_REGISTRY_REPOS)<=set(names),"required Bazel registries missing")
    need(all((external/name).is_dir() and not (external/name).is_symlink() for name in names),
         "invalid generated Bazel registry")
    return names

def bazel_registry_snapshot(output_root, destination, name=BAZEL_REGISTRY_REPO):
    """Retain the exact generated registry repository not cached by Bazel downloads."""
    need(BAZEL_REGISTRY_NAME.fullmatch(name),"unexpected Bazel registry")
    roots=list(output_root.glob("*/external/"+name))
    need(len(roots)==1 and roots[0].is_dir() and not roots[0].is_symlink(), "Bazel registry repository missing or ambiguous")
    source=roots[0]
    # It is a small generated repository; reject links rather than preserving host paths.
    entries=list(source.rglob("*"))
    need(bool(entries) and all(not p.is_symlink() and (p.is_file() or p.is_dir()) for p in entries),
         "Bazel registry repository is not portable")
    need(not destination.exists(), "Bazel registry snapshot already exists")
    shutil.copytree(source,destination)

def bazel_git_repository_names(external):
    """Select locked Git workspaces, not crates.io packages or Bazel marker files."""
    module=(UPSTREAM/"MODULE.bazel").read_text()
    direct=set(re.findall(r'git_repository\(\s*name\s*=\s*"([^"]+)"',module))
    # Local module extensions can declare Git repositories outside the root MODULE
    # (for example V8's ICU, dragonbox and FP16). Bazel's download cache does not
    # retain git fetches. Match actual canonical repositories to these declarations.
    extension_repos=set()
    for package,filename,extension in re.findall(
            r'use_extension\(\s*"//([^":]*):([^"]+)"\s*,\s*"([^"]+)"',module):
        source=(UPSTREAM/package/filename).resolve(strict=True)
        need(source.is_relative_to(UPSTREAM.resolve()) and source.is_file(),
             "invalid local Bazel module extension: "+str(source))
        for name in re.findall(r'git_repository\(\s*name\s*=\s*"([^"]+)"',source.read_text()):
            extension_repos.update(("+"+extension+"+"+name,"~"+extension+"~"+name))
    locked=set()
    for lock_path, prefix in (
        ("codex-rs/Cargo.lock","rules_rs++crate+crates__"),
        ("tools/argument-comment-lint/Cargo.lock","rules_rs++crate+argument_comment_lint_crates__"),
    ):
        with (UPSTREAM/lock_path).open("rb") as stream:
            packages=tomllib.load(stream)["package"]
        for package in packages:
            source=package.get("source","")
            if not source.startswith("git+"):
                continue
            url,_,revision=source[4:].rpartition("#")
            need(re.fullmatch(r"[0-9a-f]{40}",revision),"unlocked Git source: "+source)
            remote=urllib.parse.urlsplit(url)
            need(remote.scheme=="https" and bool(remote.hostname),"unsupported Git source: "+source)
            key=(remote.netloc+remote.path).replace("/","_")
            # rules_rs names a workspace by remote URL and the first eight commit digits.
            variants={key,key.removesuffix(".git"),key.removesuffix(".git")+".git"}
            locked.update(prefix+name+"_"+revision[:8] for name in variants)
    names=[]
    for p in external.iterdir():
        name=p.name
        if name in locked or name in extension_repos or any(
                name==n or name.endswith("+git_repository+"+n) or name.endswith("~git_repository~"+n)
                for n in direct):
            need(re.fullmatch(r"[A-Za-z0-9_.+~-]+",name) and p.is_dir(),
                 "invalid generated Git repository: "+name)
            names.append(name)
    need(bool(names),"no materialized Git repositories found")
    return sorted(names)


def bazel_git_snapshots(external, destination, output_root):
    """Archive the exact generated Git repository, including its Bazel BUILD files."""
    names=bazel_git_repository_names(external)
    destination.mkdir()
    workspace=UPSTREAM.resolve()
    for name in names:
        source=(external/name).resolve(strict=True)
        need(source.is_relative_to(output_root.resolve()) and source.is_dir(),
             "Git repository escapes Bazel output root: "+name)
        target=destination/name
        shutil.copytree(source,target,symlinks=True)
        for link in source.rglob("*"):
            if not link.is_symlink():
                need(link.is_file() or link.is_dir(),"unsupported Git repository entry: "+str(link))
                continue
            resolved=link.resolve()
            copied=target/link.relative_to(source)
            if resolved.is_relative_to(source):
                if pathlib.Path(os.readlink(link)).is_absolute():
                    copied.unlink()
                    copied.symlink_to(os.path.relpath(target/resolved.relative_to(source),copied.parent))
            elif resolved.is_relative_to(workspace) and resolved.is_file():
                # Bazel's git_repository(build_file=...) creates a workspace link.
                copied.unlink()
                shutil.copy2(resolved,copied)
            else:
                raise ValueError("non-portable Git repository link: "+str(link))
    (destination/"repositories.json").write_text(json.dumps(names)+"\n")


def bazel_git_overrides(destination):
    need(destination.is_dir() and not destination.is_symlink(),"missing Git repository snapshots")
    manifest=destination/"repositories.json"
    need(manifest.is_file() and not manifest.is_symlink(),"missing Git repository manifest")
    names=json.loads(manifest.read_text())
    need(isinstance(names,list) and bool(names) and all(isinstance(n,str) and
         re.fullmatch(r"[A-Za-z0-9_.+~-]+",n) and n not in (".","..") for n in names),
         "invalid Git repository manifest")
    need(names==sorted(set(names)) and {p.name for p in destination.iterdir()}==set(names)|{"repositories.json"},
         "Git repository manifest mismatch")
    for name in names:
        path=destination/name
        need(path.is_dir() and not path.is_symlink(),"invalid Git repository snapshot: "+name)
        yield "--override_repository="+name+"="+str(path.resolve())


def bazel_registry_overrides(directory):
    need(directory.is_dir() and not directory.is_symlink(),"missing verified Bazel registries")
    manifest=directory/"repositories.json"
    need(manifest.is_file() and not manifest.is_symlink(),"missing Bazel registry manifest")
    names=json.loads(manifest.read_text())
    need(isinstance(names,list) and all(isinstance(n,str) and BAZEL_REGISTRY_NAME.fullmatch(n) for n in names),
         "invalid Bazel registry manifest")
    need(names==sorted(set(names)) and set(BAZEL_REGISTRY_REPOS)<=set(names) and
         {p.name for p in directory.iterdir()}==set(names)|{"repositories.json"},"incomplete Bazel registry set")
    for name in names:
        path=directory/name
        need(path.is_dir() and not path.is_symlink(),"invalid Bazel registry: "+name)
        yield "--override_repository="+name+"="+str(path.resolve())

def bazel_fetch(stage,offline):
    exe=stage/"bin/bazel"
    need(output(exe,"--version")=="bazel "+LOCK["bazel"],"Bazel version mismatch")
    with tempfile.TemporaryDirectory() as d:
        cache=stage/"repository-cache";cache.mkdir(exist_ok=True)
        contents=pathlib.Path(d)/"repo-contents-cache";contents.mkdir()
        # Materialized external repositories can contain links to the old output root.
        # Only the content-addressed cache and verified small registry repository travel.
        args=[exe,"--batch","--output_user_root="+d,"fetch","--lockfile_mode=error",
              "--repository_cache="+str(cache),"--repo_contents_cache="+str(contents),"--disk_cache=","--jobs=4"]
        registries=stage/"registry-repositories"
        git_repositories=stage/"git-repositories"
        if offline:
            args.append("--repository_disable_download")
            args.extend(bazel_registry_overrides(registries))
            args.extend(bazel_git_overrides(git_repositories))
        run(*args,*BAZEL_TARGETS,cwd=UPSTREAM)
        if not offline:
            registries.mkdir()
            roots=list(pathlib.Path(d).glob("*/external/"+BAZEL_REGISTRY_REPO))
            need(len(roots)==1,"Bazel output root missing or ambiguous")
            names=bazel_registry_names(roots[0].parent)
            for name in names:
                bazel_registry_snapshot(pathlib.Path(d),registries/name,name)
            (registries/"repositories.json").write_text(json.dumps(names)+"\n")
            bazel_git_snapshots(roots[0].parent,git_repositories,pathlib.Path(d))

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
    elif group=="uv":
        (stage/"bin").mkdir()
        for name in ("uv","uvx"):shutil.copy2(pathlib.Path(shutil.which(name)).resolve(),stage/"bin"/name)
        uv_sync(stage,False)
    elif group=="pnpm":
        (stage/"bin").mkdir();(stage/"store").mkdir()
        with tempfile.TemporaryDirectory() as d:
            archive=pathlib.Path(d)/"pnpm.tgz"
            package=json.loads((UPSTREAM/"package.json").read_text())["packageManager"]
            need(package=="pnpm@"+LOCK["pnpm"]+"+sha512."+LOCK["pnpmIntegrity"],"pnpm source lock drift")
            download_checked("https://registry.npmjs.org/pnpm/-/pnpm-"+LOCK["pnpm"]+".tgz",archive,LOCK["pnpmIntegrity"],"sha512")
            import tarfile
            with tarfile.open(archive) as t:t.extractall(stage,filter="data")
        script=stage/"bin/pnpm"
        script.write_text('#!/usr/bin/env bash\nset -euo pipefail\nROOT="$(cd "$(dirname "$0")/.." && pwd)"\nexec node "$ROOT/package/bin/pnpm.cjs" "$@"\n')
        script.chmod(0o755);pnpm_install(stage,False)
    elif group=="bazel":
        (stage/"bin").mkdir()
        name="bazel-"+LOCK["bazel"]+"-linux-x86_64"
        url="https://github.com/bazelbuild/bazel/releases/download/"+LOCK["bazel"]+"/"+name
        with tempfile.TemporaryDirectory() as d:
            checksum=pathlib.Path(d)/"sha256";download(url+".sha256",checksum)
            expected=checksum.read_text().split()[0]
            need(re.fullmatch(r"[a-f0-9]{64}",expected),"invalid Bazel checksum")
            binary=stage/"bin/bazel";download_checked(url,binary,expected);binary.chmod(0o755)
            print("BAZEL_BINARY_SHA256="+expected,flush=True)
        bazel_fetch(stage,False)
    elif group=="emulator":
        sdk=pathlib.Path(os.environ["ANDROID_HOME"])
        manager=next(sdk.glob("cmdline-tools/*/bin/sdkmanager"))
        package="system-images;android-"+LOCK["deviceImage"]+";"+LOCK["deviceTarget"]+";x86_64"
        run(manager,"emulator","platform-tools",package,"platforms;android-"+str(LOCK["compileSdk"]))
        for rel in ("emulator","platform-tools","cmdline-tools","licenses","platforms/android-"+str(LOCK["compileSdk"]),
                    package.replace(";","/")):
            src=sdk/rel;dest=stage/rel;dest.parent.mkdir(parents=True,exist_ok=True)
            shutil.copytree(src,dest,symlinks=True)
        print("EMULATOR_REVISION="+(stage/"emulator/source.properties").read_text(),flush=True)
        print("SYSTEM_IMAGE_REVISION="+(stage/package.replace(";","/")/"source.properties").read_text(),flush=True)
    elif group=="llvm":llvm_prepare(stage)
    elif group=="patcher":
        for name,sha in (("hdiffpatch_v"+LOCK["hdiffpatch"]+"_sdk_android_hpatchz.zip",LOCK["hdiffpatchAndroidSha256"]),
                         ("hdiffpatch_v"+LOCK["hdiffpatch"]+"_bin_linux64.zip",LOCK["hdiffpatchLinuxSha256"])):
            download_checked("https://github.com/sisong/HDiffPatch/releases/download/v"+LOCK["hdiffpatch"]+"/"+name,stage/name,sha)

def verify(group,stage):
    if group=="wheelhouse":wheelhouse_verify(stage)
    elif group=="uv":uv_sync(stage,True)
    elif group=="pnpm":pnpm_install(stage,True)
    elif group=="bazel":bazel_fetch(stage,True)
    elif group=="llvm":llvm_verify(stage)
    elif group=="emulator":run("bash",ROOT/"tools/dev/run-emulator.sh","--probe",env={"CODEX_EMULATOR_SDK":str(stage),"EMULATOR_PROFILE":"mainstream"})
    elif group=="patcher":
        env={"CODEX_PATCHER_HOME":str(stage)}
        run("bash",ROOT/".github/scripts/prepare-android-patcher.sh","arm64-v8a","x86_64",cwd=ROOT,env=env)
        run("bash",ROOT/".github/scripts/create-android-update-fixture.sh",cwd=ROOT,env=env)

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument("command",choices=("prepare","verify"));p.add_argument("group",choices=GROUPS);p.add_argument("directory",type=pathlib.Path);a=p.parse_args()
    (prepare if a.command=="prepare" else verify)(a.group,a.directory.resolve())
if __name__=="__main__":main()
