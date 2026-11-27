#!/usr/bin/env python3
"""Content-addressed Android V8 producer; no mutable cache is a trust source."""
import argparse
import difflib
import hashlib
import json
import os
import re
import shutil
import subprocess
import tempfile
import sys
import zipfile
from datetime import datetime
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'tools/build'))
from transfer_progress import download, TransferUnavailable
from build_recipe import job_recipe

TARGETS = {"aarch64-linux-android": ("arm64-v8a", "arm64"),
           "x86_64-linux-android": ("x86_64", "x64")}
SOURCE = "2768994f664e8a6e3aba27503606c58339136e2a"
VERSION = "152.2.0"
WORKFLOW = ".github/workflows/build-native-codex.yml"
INPUTS = ("android-app/tools/prepare_android_v8.sh",
          "android-app/tools/adapt_android_v8_build.py",
          "android-app/tools/v8_toolchain_provenance.py",
          "upstream/codex/codex-rs/Cargo.lock",
          ".github/scripts/ci-android-sdk.sh")
KEY_INPUTS = (*INPUTS, ".github/actions/ci-android/action.yml",
              ".github/actions/ci-rust/action.yml", ".github/scripts/ci-llvm.sh")
PRODUCER = ".github/scripts/v8_artifacts.py"
LIMIT = 3 * 1024**3


def git(*args):
    return subprocess.check_output(["git", *args], text=True).strip()


def producer_recipe(workflow):
    return job_recipe(workflow, "v8")


def key(target, sha="HEAD"):
    if target not in TARGETS:
        raise ValueError("Unsupported V8 target")
    recipe = producer_recipe(git("show", f"{sha}:{WORKFLOW}"))
    inputs = [git("rev-parse", f"{sha}:{p}") for p in KEY_INPUTS]
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


def host_identity():
    # Only the locked OS family/version and architecture define compatibility.
    # Branding, support URLs and image metadata are not compiler inputs.
    info = {}
    for line in Path("/etc/os-release").read_text().splitlines():
        name, sep, value = line.partition("=")
        if sep and name in {"ID", "VERSION_ID"}:
            if name in info:
                raise ValueError("Duplicate host OS field: " + name)
            info[name] = value.strip('"')
    if info != {"ID": "ubuntu", "VERSION_ID": "24.04"} or os.uname().machine != "x86_64":
        raise ValueError("V8 requires Ubuntu 24.04 x86_64")
    return b"runner=x86_64\nID=ubuntu\nVERSION_ID=24.04\n"


def expected_inputs(root, target):
    if target not in TARGETS:
        raise ValueError("Unsupported V8 target")
    # V8 is built from the pristine committed lock. Android adaptation changes
    # Cargo.lock later; never calculate the V8 receipt from that mutable tree.
    subprocess.run(["git", "-C", str(root), "diff", "--quiet", "HEAD", "--", *INPUTS], check=True)
    env = dict(os.environ, RUSTUP_TOOLCHAIN="1.99.1")
    rust = subprocess.check_output(["rustc", "--version"], env=env)
    if not re.fullmatch(rb"rustc 1\.99\.1(?: [^\r\n]+)?\n", rust):
        raise ValueError("Unexpected Rust compiler version")
    ndk = Path(os.environ["ANDROID_NDK_HOME"], "source.properties").read_bytes().splitlines()
    revisions = [x for x in ndk if x.startswith(b"Pkg.Revision")]
    if len(revisions) != 1 or revisions[0].split(b"=",1)[1].strip() != b"27.2.12479018":
        raise ValueError("Unexpected NDK revision")
    result = (f"source={SOURCE}\nversion={VERSION}\ntarget={target}\n".encode() + rust
              + b"Pkg.Revision=27.2.12479018\n" + host_identity())
    return result + "".join(f"{sha256(root/p)}  {p}\n" for p in INPUTS).encode()

def verify(folder, target, expected):
    files = names(target)
    if not folder.is_dir() or {p.name for p in folder.iterdir()} != set(files):
        raise ValueError("Unexpected V8 artifact file set")
    if any(p.is_symlink() or not p.is_file() or p.stat().st_size <= 0 for p in folder.iterdir()):
        raise ValueError("Missing or invalid V8 artifact file")
    observed = (folder/"BUILD_INPUTS").read_bytes()
    if observed != expected:
        difference = "\n".join(difflib.unified_diff(
            observed.decode("utf-8", errors="replace").splitlines(),
            expected.decode("utf-8", errors="replace").splitlines(),
            fromfile="producer BUILD_INPUTS", tofile="consumer BUILD_INPUTS", lineterm=""))
        raise ValueError("V8 build input mismatch:\n" + difference)
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
    if (artifact.get("expired") or artifact.get("name") not in ("v8-binary-"+expected_key, "v8-recovery-"+expected_key)
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
    recovery = artifact["name"].startswith("v8-recovery-")
    job_name = ("编译 "+TARGETS[target][0]+" 核心") if recovery else ("V8 官方源码产物 · "+TARGETS[target][0])
    step_name = "从固定官方源码重建并核验 V8 产物" if recovery else "从官方源码编译 V8"
    matches = [j for j in response["jobs"]
               if j.get("name","").rsplit(" / ",1)[-1] == job_name
               and j.get("head_sha") == old and j.get("run_attempt") == attempt
               and j.get("status") == "completed" and j.get("conclusion") == "success"
               and any(s.get("name") == step_name and s.get("conclusion") == "success"
                       for s in j.get("steps",[]))]
    return len(matches) == 1 and timestamp(matches[0]["started_at"]) <= timestamp(artifact["created_at"]) <= timestamp(matches[0]["completed_at"])


def current_artifact(repo, run_id, expected_key, api_call=api):
    """Resolve the current run's exact ABI artifact, never a stale other run."""
    name = "v8-binary-" + expected_key
    response = api_call(repo, f"actions/runs/{run_id}/artifacts?name={name}&per_page=100")
    rows = response["artifacts"]
    if response["total_count"] > len(rows):
        raise ValueError("Incomplete current V8 artifact index")
    matches = [a for a in rows if a.get("name") == name and not a.get("expired", True)]
    if len(matches) != 1:
        raise ValueError(f"Expected one current-run V8 artifact {name}, found {len(matches)}; rerun its producer, not only its consumer")
    artifact = matches[0]
    if (type(artifact.get("id")) is not int or artifact["id"] <= 0
            or not re.fullmatch(r"sha256:[0-9a-f]{64}", artifact.get("digest") or "")
            or not 0 < artifact.get("size_in_bytes", 0) <= LIMIT):
        raise ValueError("Invalid current-run V8 artifact descriptor")
    return artifact


def download_verified(repo, artifact, folder, target, expected):
    if folder.exists():
        raise ValueError("V8 consumer requires an empty destination")
    folder.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=folder.parent) as tmp:
        archive = Path(tmp) / "source.zip"
        download(f"repos/{repo}/actions/artifacts/{artifact['id']}/zip", archive, artifact['size_in_bytes'])
        if sha256(archive) != artifact["digest"].removeprefix("sha256:"):
            raise ValueError("V8 GitHub artifact digest mismatch")
        extracted = Path(tmp) / "verified"
        extract(archive, extracted, target)
        verify(extracted, target, expected)
        extracted.rename(folder)
    print(f"Verified V8 artifact ID {artifact['id']} for {target}")


def restore(repo,target,source,folder,expected_key,expected):
    # Query immutable indexed artifacts; only original successful source
    # producers qualify, even while other jobs in that workflow are running.
    rejected = 0
    for prefix in ("v8-binary-", "v8-recovery-"):
        for page in range(1, 11):
            response = api(repo, f"actions/artifacts?name={prefix}{expected_key}&per_page=100&page={page}")
            rows = response["artifacts"]
            for artifact in rows:
                if not direct_producer(repo, artifact, target, expected_key, source):
                    continue
                try:
                    download_verified(repo, artifact, folder, target, expected)
                except (ValueError, zipfile.BadZipFile, TransferUnavailable) as exc:
                    rejected += 1
                    print(f"::warning::Rejected V8 artifact {artifact['id']}: {exc}", flush=True)
                    if rejected >= 2:
                        return False
                    continue
                print(f"Verified V8 artifact {artifact['id']} from run {artifact['workflow_run']['id']}")
                return True
            if len(rows) < 100:
                break
        else:
            raise ValueError("V8 index exceeds safe scan limit")
    return False


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("command",choices=("key","inputs","restore","verify","consume"))
    p.add_argument("--target",required=True,choices=TARGETS)
    p.add_argument("--folder",type=Path)
    p.add_argument("--repo")
    p.add_argument("--key")
    a=p.parse_args()
    if a.command == "inputs":
        import sys
        sys.stdout.buffer.write(expected_inputs(Path(os.environ["GITHUB_WORKSPACE"]), a.target))
        return
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
        if a.command == "consume":
            if not a.repo:
                p.error("Consume requires repository")
            try:
                artifact = current_artifact(a.repo, int(os.environ["GITHUB_RUN_ID"]), fingerprint)
                download_verified(a.repo, artifact, folder, a.target, expected)
            except (ValueError, zipfile.BadZipFile, TransferUnavailable) as exc:
                print(f"::warning::Current V8 artifact rejected: {exc}", flush=True)
                if not restore(a.repo, a.target, source, folder, fingerprint, expected):
                    # Workflow must rebuild and independently verify before Cargo.
                    with open(os.environ["GITHUB_OUTPUT"], "a") as out:
                        print("rebuild=true", file=out)
                    return
            with open(os.environ["GITHUB_OUTPUT"], "a") as out:
                print("rebuild=false", file=out)
        archive,binding=verify(folder,a.target,expected)
        with open(os.environ["GITHUB_ENV"],"a") as out:
            print(f"RUSTY_V8_ARCHIVE={folder/archive}",file=out)
            print(f"RUSTY_V8_SRC_BINDING_PATH={folder/binding}",file=out)


if __name__=="__main__":
    main()
