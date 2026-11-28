#!/usr/bin/env python3
"""Assemble the exact 777 signed precompiled runtime and 111's own small command adapter."""
import argparse
import hashlib
import json
import pathlib
import re
import shutil
import subprocess
import tarfile
import tempfile
import sys
import zipfile

GIT_VERSION = "2.56.1"
GIT_DEB = ("https://packages.termux.dev/apt/termux-main/pool/main/g/git/git_2.56.1_aarch64.deb",
           "3343f56fc99e2f0631a553a39789769495435cb07b6ffb3c71a7fcb7bd561e95")
GIT_SOURCE = ("https://mirrors.kernel.org/pub/software/scm/git/git-2.56.1.tar.xz",
              "f0b0bf89264b9bd2e1c640363e7529643989da76281600473c50745cd34a607f")
TERMUX_RECIPE_COMMIT = "8a9e362e0083070452d9d1b1ed84651a57607a1c"
SYSTEM = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libz.so", "libandroid.so",
          "libatomic.so", "libpthread.so", "librt.so", "libaaudio.so", "libOpenSLES.so", "libmediandk.so",
          "libjnigraphics.so", "libEGL.so", "libGLESv2.so", "libvulkan.so"}


ROOT = pathlib.Path(__file__).resolve().parents[2]
LOCK = json.loads((ROOT / "tools/runtime/runtime-lock.json").read_text())
sys.path.insert(0, str(ROOT / "tools/runtime"))
from runtime_versions import package_records, verify_versions, versions_for
VERSIONS = versions_for("arm64-v8a")

def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()

def pack_runtime_data(data, assets):
    """One physical ZIP entry per byte-identical payload, with exact logical paths.

    Preserve all original tool and library lookup paths (including ABI aliases).
    The APK-signed manifest describes every file; Android recreates identical
    files as hard links inside the private runtime directory. No dependency or
    dynamically loaded module is removed based on a guessed usage list.
    """
    files = []
    canonical = {}
    out = assets / "development-runtime.zip"
    manifest = assets / "development-runtime.files.tsv"
    with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for path in sorted(data.rglob("*"), key=lambda p: p.relative_to(data).as_posix()):
            if path.is_symlink() or not (path.is_file() or path.is_dir()):
                raise ValueError("Invalid runtime file: " + str(path))
            if path.is_dir():
                continue
            name = path.relative_to(data).as_posix()
            if any(c in name for c in "\t\r\n\\") or any(p in ("", ".", "..") for p in name.split("/")):
                raise ValueError("Invalid runtime manifest path: " + name)
            sha = digest(path)
            size = path.stat().st_size
            files.append((name, sha, size))
            if sha in canonical:
                if canonical[sha][1] != size:
                    raise ValueError("Runtime digest/size conflict")
                continue
            canonical[sha] = (name, size)
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = 0o100444 << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            with path.open("rb") as source, archive.open(info, "w") as target:
                shutil.copyfileobj(source, target, length=1024 * 1024)
    if not files:
        raise ValueError("Empty runtime payload")
    manifest.write_bytes("".join(f"{name}\t{sha}\t{size}\n" for name, sha, size in files).encode("utf-8"))
    (assets / "development-runtime.sha256").write_text(digest(out) + "\n")
    logical = sum(size for _, _, size in files)
    unique = sum(size for _, size in canonical.values())
    print(json.dumps({"runtime_archive_bytes": out.stat().st_size,
                      "runtime_logical_bytes": logical, "runtime_unique_bytes": unique,
                      "runtime_duplicate_bytes_removed": logical - unique,
                      "runtime_logical_files": len(files), "runtime_unique_files": len(canonical)}))


def fetch(url, sha, directory):
    path = directory / url.rsplit("/", 1)[-1]
    if path.is_file():
        if digest(path) != sha:
            raise ValueError(f"Digest mismatch: {url}")
        return path
    # A TLS handshake failure (curl 35) is not covered by the default retry
    # set. Retry bounded transport failures without changing the source pin.
    partial = path.with_name(path.name + ".partial")
    try:
        subprocess.run(["curl", "--fail", "--location", "--retry", "3",
                        "--retry-all-errors", "--connect-timeout", "30",
                        "--max-time", "600", "--retry-max-time", "900",
                        "--proto", "=https", "--proto-redir", "=https",
                        url, "-o", str(partial)], check=True)
        if digest(partial) != sha:
            raise ValueError(f"Digest mismatch: {url}")
        partial.replace(path)
    finally:
        partial.unlink(missing_ok=True)
    return path

def needed(path, abi, executable=False):
    def read(*args):
        return subprocess.check_output(["readelf", *args, str(path)], text=True)
    header = read("-h")
    machine = "AArch64" if abi == "arm64-v8a" else "X86-64"
    if "ELF64" not in header or machine.lower() not in header.lower():
        raise ValueError(f"Wrong ELF ABI: {path}")
    program = read("-Wl")
    aligns = re.findall(r"^\s*LOAD\s+.*\s(0x[0-9a-fA-F]+)\s*$", program, re.M)
    if not aligns or min(int(x, 16) for x in aligns) < 16384:
        raise ValueError(f"Not 16 KiB page aligned: {path}")
    if executable and "/system/bin/linker64" not in program:
        raise ValueError(f"Not an Android executable: {path}")
    return set(re.findall(r"Shared library: \[([^]]+)\]", read("-Wd")))

def patch_git_shell(binary):
    original = b"/data/data/com.termux/files/usr/bin/sh\0"
    replacement = b"/system/bin/sh".ljust(len(original), b"\0")
    if binary.count(original) != 1:
        raise ValueError("Unexpected compiled Git shell path")
    return binary.replace(original, replacement)


def git_corresponding_source(work, manifest, output):
    source = fetch(*GIT_SOURCE, work)
    recipe = work / "termux-recipes"
    subprocess.run(["git", "init", "-q", str(recipe)], check=True)
    subprocess.run(["git", "-C", str(recipe), "fetch", "--depth=1", "--no-tags",
                    "https://github.com/termux/termux-packages.git", TERMUX_RECIPE_COMMIT], check=True)
    commit = subprocess.check_output(["git", "-C", str(recipe), "rev-parse", "FETCH_HEAD"], text=True).strip()
    if commit != TERMUX_RECIPE_COMMIT:
        raise ValueError("Termux corresponding source commit mismatch")
    recipe_text = subprocess.check_output(
        ["git", "-C", str(recipe), "show", f"{commit}:packages/git/build.sh"], text=True)
    if f'TERMUX_PKG_VERSION="{GIT_VERSION}"' not in recipe_text.splitlines() or (
            f"TERMUX_PKG_SHA256={GIT_SOURCE[1]}" not in recipe_text.splitlines()):
        raise ValueError("Termux source recipe does not match locked Git source")
    recipe_archive = work / "termux-packages.tar.gz"
    subprocess.run(["git", "-C", str(recipe), "archive", "--format=tar.gz",
                    "--prefix=termux-packages/", "-o", str(recipe_archive), commit], check=True)
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(".partial")
    with tarfile.open(temporary, "w:xz") as archive:
        archive.add(source, arcname=source.name)
        archive.add(recipe_archive, arcname=recipe_archive.name)
        archive.add(manifest, arcname="termux-git-packages.tsv")
        archive.add(pathlib.Path(__file__).resolve(), arcname="prepare_prebuilt_developer_runtime.py")
        root = pathlib.Path(__file__).resolve().parents[2]
        for script in sorted((root / "tools/runtime").glob("*.sh")):
            archive.add(script, arcname="tools/runtime/" + script.name)
    temporary.replace(output)
    return digest(output)



def asset_root(prepared, group):
    return prepared / group / "assets/runtime" / group

def verify_source(prepared, abi, source):
    manifest = asset_root(prepared,"git") / abi / "packages.tsv"
    with tarfile.open(source, "r:xz") as archive:
        names = archive.getnames()
        if len(names) != len(set(names)):
            raise ValueError("duplicate Git source archive entries")
        for name in (GIT_SOURCE[0].rsplit("/",1)[-1], "termux-packages.tar.gz", "termux-git-packages.tsv"):
            member = archive.getmember(name)
            if not member.isfile():
                raise ValueError("invalid Git corresponding source entry: " + name)
        with archive.extractfile(GIT_SOURCE[0].rsplit("/",1)[-1]) as stream:
            if hashlib.file_digest(stream,"sha256").hexdigest() != GIT_SOURCE[1]:
                raise ValueError("Git upstream source digest mismatch")
        with archive.extractfile("termux-git-packages.tsv") as stream:
            if stream.read() != manifest.read_bytes():
                raise ValueError("Git corresponding source package manifest mismatch")
        with archive.extractfile("termux-packages.tar.gz") as stream, tarfile.open(fileobj=stream, mode="r:gz") as recipe:
            text = recipe.extractfile("termux-packages/packages/git/build.sh").read().decode()
            if f'TERMUX_PKG_VERSION="{GIT_VERSION}"' not in text.splitlines() or f"TERMUX_PKG_SHA256={GIT_SOURCE[1]}" not in text.splitlines():
                raise ValueError("Git corresponding source recipe mismatch")
    return digest(source)

def prepare_source(prepared, abi, output):
    verify_versions(prepared, abi)
    with tempfile.TemporaryDirectory(prefix="git-source-") as directory:
        git_corresponding_source(pathlib.Path(directory), asset_root(prepared,"git") / abi / "packages.tsv", output)
    verify_source(prepared, abi, output)

def copy_regular_tree(source, target):
    if not source.is_dir() or source.is_symlink():
        raise ValueError("runtime tree missing: " + str(source))
    for path in source.rglob("*"):
        if path.is_symlink() or not (path.is_file() or path.is_dir()):
            raise ValueError("unexpected runtime entry: " + str(path))
    shutil.copytree(source,target)

def prepare(abi, native, assets, prepared, clang, git_source_output):
    records = verify_versions(prepared, abi)
    source = prepared / "git-corresponding-source.tar.xz"
    source_digest = verify_source(prepared, abi, source)
    native.mkdir(parents=True, exist_ok=True)
    assets.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="111-runtime-") as directory:
        work = pathlib.Path(directory)
        data = work / "data"
        data.mkdir()
        commands = {}
        binaries = {}
        def add(alias, group, original, target, mode="direct", git_shell=False):
            if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9._+-]*",alias) or alias in commands:
                raise ValueError("invalid or duplicate command alias: " + alias)
            if not re.fullmatch(r"libcodex_[a-zA-Z0-9_]+\.so",target):
                raise ValueError("invalid native target: " + target)
            src = prepared / group / "jniLibs" / abi / original
            if not src.is_file() or src.is_symlink():
                raise ValueError("prebuilt command missing: " + str(src))
            blob = src.read_bytes()
            needle = b"/data/data/com.termux/files/usr/bin/sh\0"
            if git_shell or (group=="git" and alias.startswith("git-") and needle in blob):
                blob = patch_git_shell(blob)
            dst = native / target
            h = hashlib.sha256(blob).hexdigest()
            if target in binaries:
                if binaries[target] != h:
                    raise ValueError("conflicting command binaries")
            else:
                dst.write_bytes(blob)
                dst.chmod(0o755)
                binaries[target] = h
            commands[alias] = (target,group,mode)
        for group in ("git","node","python"):
            base = asset_root(prepared,group)
            target = data/group
            target.mkdir()
            copy_regular_tree(base/abi/"lib", target/"lib")
            if group in ("git","python"):
                copy_regular_tree(base/abi/"home", target/"home")
            if group=="node":
                copy_regular_tree(base/"npm",target/"npm")
            copy_regular_tree(base/"notices",target/"notices")
            if not any((target/"notices").iterdir()):
                raise ValueError("third-party license notices missing: " + group)
            shutil.copyfile(base/abi/"packages.tsv",target/"packages.tsv")
        add("git","git","libdsh_git.so","libcodex_git.so",git_shell=True)
        for alias in ("curl","jq","rg"):
            add(alias,"git",f"libdsh_{alias}.so",f"libcodex_{alias}.so")
        add("node","node","libdsh_node.so","libcodex_node.so")
        add("npm","node","libdsh_node.so","libcodex_node.so","npm")
        add("python","python","libdsh_python.so","libcodex_python.so")
        add("python3","python","libdsh_python.so","libcodex_python.so")
        add("pip","python","libdsh_python.so","libcodex_python.so","pip")
        add("pip3","python","libdsh_python.so","libcodex_python.so","pip")
        helpers = {}
        for line in (asset_root(prepared,"git")/abi/"helpers.tsv").read_text().splitlines():
            alias, original = line.split("\t")
            if not re.fullmatch(r"libdsh_git(?:_helper_[0-9a-f]{20})?\.so",original) or alias in helpers:
                raise ValueError("invalid Git helper manifest")
            helpers[alias] = original
        remote = helpers["git-remote-http"]
        if helpers["git-remote-https"] != remote:
            raise ValueError("Git HTTP/HTTPS helper differs")
        for alias, original in sorted(helpers.items()):
            if alias == "git":
                if (prepared / "git/jniLibs" / abi / original).read_bytes() != (prepared / "git/jniLibs" / abi / "libdsh_git.so").read_bytes():
                    raise ValueError("Git executable alias differs from primary binary")
                continue
            if (prepared / "git/jniLibs" / abi / original).read_bytes() == (prepared / "git/jniLibs" / abi / "libdsh_git.so").read_bytes():
                target="libcodex_git.so"
            elif original==remote:
                target="libcodex_git_remote_http.so"
            else:
                target=original.replace("libdsh_", "libcodex_",1)
            add(alias,"git",original,target,git_shell=original in ("libdsh_git.so",remote))
        # Check the complete loader closure separately for each CLI group.
        for group in ("git","node","python"):
            libdir=data/group/"lib"
            queue=[(native/target,True) for target,g,_ in set(commands.values()) if g==group]
            if group=="python":
                queue.extend((p,False) for p in (data/"python/home/lib/python3.14").rglob("*.so"))
            seen=set()
            while queue:
                path, executable=queue.pop()
                if path in seen:
                    continue
                seen.add(path)
                for dep in sorted(needed(path,abi,executable)-SYSTEM):
                    if not re.fullmatch(r"lib[A-Za-z0-9_+.-]+\.so(?:\.[A-Za-z0-9.]+)?",dep):
                        raise ValueError("invalid runtime dependency: "+dep)
                    resolved=libdir/dep
                    if not resolved.is_file():
                        raise ValueError(f"{group}: unresolved runtime dependency {dep} ({path.name})")
                    queue.append((resolved,False))
            # DT_NEEDED does not cover dlopen, ctypes, or user-supplied native
            # modules. Report candidates for measured pruning, never silently
            # remove an otherwise supported development capability.
            referenced = {p.name for p in seen if p.parent == libdir}
            unreferenced = sorted(p for p in libdir.iterdir()
                                  if p.is_file() and p.name not in referenced)
            print(json.dumps({"runtime_group": group,
                              "dependency_closure_files": sorted(referenced),
                              "dynamic_load_candidates": [
                                  {"name": p.name, "bytes": p.stat().st_size, "sha256": digest(p)}
                                  for p in unreferenced]}))
        for relative in ("git/home/share/git-core/templates","node/npm/package/bin/npm-cli.js",
                         "python/home/lib/python3.14/encodings/__init__.py",
                         "python/home/lib/python3.14/site-packages/pip/__main__.py"):
            if not (data/relative).exists():
                raise ValueError("incomplete runtime data: "+relative)
        header=work/"developer_tool_commands.h"
        header.write_text("static const struct command commands[] = {\n" +
            "".join("    {"+",".join(json.dumps(x) for x in (alias,*row))+"},\n" for alias,row in sorted(commands.items()))+"};\n")
        launcher=native/"libcodex_tool_launcher.so"
        subprocess.run([str(clang),"-std=c11","-O2","-Wall","-Wextra","-Werror","-fPIE","-pie",
                        "-Wl,-z,max-page-size=16384","-Wl,-z,common-page-size=16384",
                        "-I"+str(work),str(ROOT/"android-app/tools/developer_tool_launcher.c"),"-o",str(launcher)],check=True)
        if needed(launcher,abi,True)-SYSTEM:
            raise ValueError("command launcher requires non-system libraries")
        launcher.chmod(0o755)
        binaries[launcher.name]=digest(launcher)
        (assets/"development-runtime.commands.tsv").write_text("".join(alias+"\t"+row[0]+"\n" for alias,row in sorted(commands.items())))
        pack_runtime_data(data, assets)
        versions=versions_for(abi)
        (assets/"development-runtime.version").write_text(json.dumps(versions,sort_keys=True)+"\n")
        provenance={"abi":abi,"source777":LOCK["source"],"versions":versions,"packages":records,"binaries":binaries,
                    "gitHttps":True,"gitCorrespondingSourceVerified":True,
                    "git":{"version":GIT_VERSION,"packageSha256":records["git"]["git"]["sha256"],
                           "termuxRecipeCommit":TERMUX_RECIPE_COMMIT,"upstreamSourceSha256":GIT_SOURCE[1],
                           "sourceArchiveSha256":source_digest,"binaries":{k:binaries[k] for k in ("libcodex_git.so","libcodex_git_remote_http.so")}}}
        (assets/"development-runtime.provenance.json").write_text(json.dumps(provenance,indent=2)+"\n")
        git_source_output.parent.mkdir(parents=True,exist_ok=True)
        shutil.copyfile(source,git_source_output)
        print(json.dumps(provenance,indent=2))

if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("abi",choices=("arm64-v8a","x86_64"))
    p.add_argument("--prepared-dir",type=pathlib.Path,required=True)
    p.add_argument("--prepare-source",action="store_true")
    p.add_argument("--native",type=pathlib.Path)
    p.add_argument("--assets",type=pathlib.Path)
    p.add_argument("--clang",type=pathlib.Path)
    p.add_argument("--git-source-output",type=pathlib.Path,required=True)
    a=p.parse_args()
    if a.prepare_source:
        prepare_source(a.prepared_dir,a.abi,a.git_source_output)
    else:
        if not all((a.native,a.assets,a.clang)):
            p.error("APK assembly requires native, assets and clang")
        prepare(a.abi,a.native,a.assets,a.prepared_dir,a.clang,a.git_source_output)
