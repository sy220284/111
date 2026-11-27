#!/usr/bin/env python3
"""Content-addressed Android V8 producer; no mutable cache is a trust source."""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import tempfile
import zipfile
from datetime import datetime
from pathlib import Path

TARGETS = {"aarch64-linux-android": ("arm64-v8a", "arm64"),
           "x86_64-linux-android": ("x86_64", "x64")}
SOURCE = "2768994f664e8a6e3aba27503606c58339136e2a"
VERSION = "152.2.0"
WORKFLOW = ".github/workflows/build-native-codex.yml"
INPUTS = ("android-app/tools/prepare_android_v8.sh",
          "android-app/tools/adapt_android_v8_build.py",
          "android-app/tools/v8_toolchain_provenance.py",
          "upstream/codex/codex-rs/Cargo.lock")
PRODUCER = ".github/scripts/v8_artifacts.py"
LIMIT = 3 * 1024**3


def git(*args):
    return subprocess.check_output(["git", *args], text=True).strip()


def producer_recipe(workflow):
    marker = "\n  v8:\n"
    if marker not in workflow:
        raise ValueError("Missing independent V8 producer")
    return re.split(r"(?m)^  [a-z][a-z0-9-]*:\s*$", workflow.split(marker,1)[1], maxsplit=1)[0]


def key(target, sha="HEAD"):
    if target not in TARGETS:
        raise ValueError("Unsupported V8 target")
    recipe = producer_recipe(git("show", f"{sha}:{WORKFLOW}"))
    inputs = [git("rev-parse", f"{sha}:{p}") for p in (*INPUTS, PRODUCER)]
    return hashlib.sha256(json.dumps([target, recipe, inputs], separators=(",",":")).encode()).hexdigest()


def names(target):
    if target not in TARGETS:
        raise ValueError("Unsupported V8 target")
    return (f"librusty_v8_ptrcomp_sandbox_release-{target}.a",
            f"src_binding_ptrcomp_sandbox_release-{target}.rs",
            "BUILD_INPUTS", "SOURCE_PROVENANCE", "SHA256SUMS")


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream,"sha256").hexdigest()


def expected_inputs(root, target):
    env = dict(os.environ, RUSTUP_TOOLCHAIN="1.99.1")
    rust = subprocess.check_output(["rustc","--version"], env=env)
    ndk = Path(os.environ["ANDROID_NDK_HOME"], "source.properties").read_bytes().splitlines()
    revisions = [x for x in ndk if x.startswith(b"Pkg.Revision")]
    if len(revisions) != 1 or revisions[0].split(b"=",1)[1].strip() != b"27.2.12479018":
        raise ValueError("Unexpected NDK revision")
    result = (f"source={SOURCE}\nversion={VERSION}\ntarget={target}\n".encode() + rust
              + revisions[0]+b"\n"+f"runner={os.uname().machine}\n".encode()
              + Path("/etc/os-release").read_bytes())
    return result + "".join(f"{sha256(root/p)}  {p}\n" for p in INPUTS).encode()


def verify(folder, target, expected):
    files = names(target)
    if not folder.is_dir() or {p.name for p in folder.iterdir()} != set(files):
        raise ValueError("Unexpected V8 artifact file set")
    if any(p.is_symlink() or not p.is_file() or p.stat().st_size <= 0 for p in folder.iterdir()):
        raise ValueError("Missing or invalid V8 artifact file")
    if (folder/"BUILD_INPUTS").read_bytes() != expected:
        raise ValueError("V8 build input mismatch")
    hashes = "".join(f"{sha256(folder/p)}  {p}\n" for p in files[:-1])
    if (folder/"SHA256SUMS").read_text() != hashes:
        raise ValueError("V8 content digest mismatch")
    lines = set((folder/"SOURCE_PROVENANCE").read_text().splitlines())
    required = {f"commit={SOURCE}", f"target={target}",
                "v8_enable_sandbox = true","v8_enable_pointer_compression = true",
                f'v8_target_cpu = "{TARGETS[target][1]}"','target_os = "android"'}
    if not required <= lines:
        raise ValueError("V8 source or sandbox provenance mismatch")
    with (folder/files[0]).open("rb") as archive:
        if archive.read(8) != b"!<arch>\n":
            raise ValueError("V8 archive format mismatch")
    return files[:2]


def extract(archive, output, target):
    expected = set(names(target))
    with zipfile.ZipFile(archive) as z:
        entries = z.infolist()
        if (len(entries) != len(expected) or {x.filename for x in entries} != expected
            or any(x.is_dir() or x.file_size <= 0 for x in entries)
            or sum(x.file_size for x in entries) > LIMIT):
            raise ValueError("Unexpected V8 ZIP entries")
        output.mkdir(parents=True, exist_ok=False)
        for info in entries:
            with z.open(info) as src, (output/info.filename).open("xb") as dst:
                shutil.copyfileobj(src,dst)


def api(repo, path):
    return json.loads(subprocess.check_output(["gh","api",f"repos/{repo}/{path}"],text=True))


def timestamp(s):
    return datetime.fromisoformat(s.replace("Z","+00:00"))


def direct_producer(repo, artifact, target, expected_key, source, api_call=api, ancestor=None, fingerprint=key):
    if ancestor is None:
        def ancestor(old,new):
            if subprocess.run(["git","cat-file","-e",old+"^{commit}"],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode:
                return False
            status = subprocess.run(["git","merge-base","--is-ancestor",old,new]).returncode
            if status not in (0,1):
                raise ValueError("Cannot establish V8 source ancestry")
            return status == 0
    if (artifact.get("expired") or artifact.get("name") != "v8-binary-"+expected_key
        or not re.fullmatch(r"sha256:[0-9a-f]{64}",artifact.get("digest") or "")
        or type(artifact.get("id")) is not int or not 0 < artifact.get("size_in_bytes",0) <= LIMIT):
        return False
    rid = (artifact.get("workflow_run") or {}).get("id")
    if not rid or rid == int(os.environ.get("GITHUB_RUN_ID","0")):
        return False
    run = api_call(repo,f"actions/runs/{rid}")
    old = run.get("head_sha","")
    if ((run.get("head_repository") or {}).get("full_name") != repo
        or run.get("path","").split("@",1)[0] not in (WORKFLOW,".github/workflows/ci.yml")
        or run.get("event") not in ("pull_request","push","workflow_dispatch","workflow_run")
        or not re.fullmatch("[0-9a-f]{40}",old) or not ancestor(old,source)
        or fingerprint(target,old) != expected_key):
        return False
    attempt = run["run_attempt"]
    response = api_call(repo,f"actions/runs/{rid}/attempts/{attempt}/jobs?per_page=100")
    if response["total_count"] > len(response["jobs"]):
        raise ValueError("Incomplete V8 job index")
    matches = [j for j in response["jobs"]
               if j.get("name","").rsplit(" / ",1)[-1] == "V8 官方源码产物 · "+TARGETS[target][0]
               and j.get("head_sha") == old and j.get("run_attempt") == attempt
               and j.get("status") == "completed" and j.get("conclusion") == "success"
               and any(s.get("name") == "从官方源码编译 V8" and s.get("conclusion") == "success"
                       for s in j.get("steps",[]))]
    return len(matches) == 1 and timestamp(matches[0]["started_at"]) <= timestamp(artifact["created_at"]) <= timestamp(matches[0]["completed_at"])


def restore(repo,target,source,folder,expected_key,expected):
    # Query immutable indexed artifacts; only original successful source
    # producers qualify, even while other jobs in that workflow are running.
    for page in range(1,11):
        response=api(repo,f"actions/artifacts?name=v8-binary-{expected_key}&per_page=100&page={page}")
        rows=response["artifacts"]
        for artifact in rows:
            if not direct_producer(repo,artifact,target,expected_key,source):
                continue
            with tempfile.TemporaryDirectory(dir=folder.parent) as tmp:
                archive=Path(tmp)/"source.zip"
                with archive.open("wb") as stream:
                    subprocess.run(["gh","api",f"repos/{repo}/actions/artifacts/{artifact['id']}/zip"],stdout=stream,check=True)
                if sha256(archive) != artifact["digest"].removeprefix("sha256:"):
                    raise ValueError("V8 GitHub artifact digest mismatch")
                extracted=Path(tmp)/"verified"
                extract(archive,extracted,target)
                verify(extracted,target,expected)
                extracted.rename(folder)
            print(f"Verified V8 source artifact {artifact['id']} from run {artifact['workflow_run']['id']}")
            return True
        if len(rows)<100:
            return False
    raise ValueError("V8 index exceeds safe scan limit")


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("command",choices=("key","restore","verify"))
    p.add_argument("--target",required=True,choices=TARGETS)
    p.add_argument("--folder",type=Path)
    p.add_argument("--repo")
    p.add_argument("--key")
    a=p.parse_args()
    source=git("rev-parse","HEAD")
    fingerprint=key(a.target,source)
    if a.command=="key":
        print(fingerprint)
        return
    if a.key != fingerprint or not a.folder:
        p.error("V8 key or directory mismatch")
    folder=a.folder.resolve()
    expected=expected_inputs(Path(os.environ["GITHUB_WORKSPACE"]),a.target)
    if a.command=="restore":
        if not a.repo or folder.exists():
            p.error("Restore requires repository and empty destination")
        folder.parent.mkdir(parents=True,exist_ok=True)
        restored=restore(a.repo,a.target,source,folder,fingerprint,expected)
        with open(os.environ["GITHUB_OUTPUT"],"a") as out:
            print("restored="+str(restored).lower(),file=out)
    else:
        archive,binding=verify(folder,a.target,expected)
        with open(os.environ["GITHUB_ENV"],"a") as out:
            print(f"RUSTY_V8_ARCHIVE={folder/archive}",file=out)
            print(f"RUSTY_V8_SRC_BINDING_PATH={folder/binding}",file=out)


if __name__=="__main__":
    main()
