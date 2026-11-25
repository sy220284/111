#!/usr/bin/env python3
"""Select immutable precompiled Android release payloads, never build external tools."""
import argparse
import hashlib
import json
import lzma
import pathlib
import re
import shutil
import subprocess
import tarfile
import tempfile
import zipfile

DSH = {
    "arm64-v8a": ("AndroidDSH-0.2.0-arm64-v8a.apk", "1b58d871de59da6ddac5dde78107c778a5926dd46748ac55f9953cc4e8a8dcbd"),
    "x86_64": ("AndroidDSH-0.2.0-x86_64.apk", "8489da96aa6b22e2cac53b1ace1bcea8c2219db5050602ff94b9f85be616adcf"),
}
CURL = ("https://github.com/vvb2060/curl-android/releases/download/v8.18.0/tool-release.apk.xz",
        "0aa32165eb4a9cb8a56e13053cd3bc80cccb0c761b4daf9688c3e3667522bb4b")
GIT = ("https://github.com/rmyndharis/VSCodroid/releases/download/v1.11.0/app-release.apk",
       "4f83ff6fd8a70f19ee04ad93861d68522a2936c203f6ec941e9c12c4527051a3")
GIT_MANIFEST = ("https://github.com/rmyndharis/VSCodroid/releases/download/v1.11.0/build-manifest.txt",
                "b5209f3349833aba6bb4824a37cddc8f29b5c9e5709b68e7a26536dbfa75dd3e")
GIT_VERSION = "2.56.1"
GIT_DEB = ("https://packages.termux.dev/apt/termux-main/pool/main/g/git/git_2.56.1_aarch64.deb",
           "3343f56fc99e2f0631a553a39789769495435cb07b6ffb3c71a7fcb7bd561e95")
GIT_SOURCE = ("https://mirrors.kernel.org/pub/software/scm/git/git-2.56.1.tar.xz",
              "f0b0bf89264b9bd2e1c640363e7529643989da76281600473c50745cd34a607f")
TERMUX_RECIPE_COMMIT = "8a9e362e0083070452d9d1b1ed84651a57607a1c"
VSCODROID_COMMIT = "a8bee507535845a3a6ab89265b455b24cab7e878"
PIP = "26.3"
# PyPI publishes a different digest for the source tarball. Pin the exact wheel.
PIP_WHEEL = ("https://files.pythonhosted.org/packages/07/25/ee8c38ae72d66b844ce8eb9711ba905dba21ca1a8a0c960057c41cd23337/pip-26.3-py3-none-any.whl",
             "f3b60b20ffaadd4415ed7957212a38ac63fa4c5fa375bd0f4a797777940fad13")
SYSTEM = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libz.so", "libandroid.so",
          "libatomic.so", "libaaudio.so", "libOpenSLES.so", "libmediandk.so",
          "libjnigraphics.so", "libEGL.so", "libGLESv2.so", "libvulkan.so"}

def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()

def fetch(url, sha, directory):
    path = directory / url.rsplit("/", 1)[-1]
    if not path.is_file():
        subprocess.run(["curl", "--fail", "--location", "--retry", "3", "--proto", "=https",
                        "--proto-redir", "=https", url, "-o", str(path)], check=True)
    if digest(path) != sha:
        raise ValueError(f"Digest mismatch: {url}")
    return path

def relative(name):
    path = pathlib.PurePosixPath(name)
    if path.is_absolute() or not path.parts or ".." in path.parts or "\\" in name:
        raise ValueError(f"Unsafe archive path: {name}")
    return path

def copy_zip(archive, name, dest):
    dest.parent.mkdir(parents=True, exist_ok=True)
    with archive.open(name) as source, dest.open("wb") as output:
        shutil.copyfileobj(source, output)

def copy_tar_tree(archive, prefix, dest):
    prefix = prefix.rstrip("/") + "/"
    selected = set()
    for member in archive.getmembers():
        name = member.name.removeprefix("./")
        if not name.startswith(prefix) or name == prefix:
            continue
        target = dest.joinpath(*relative(name[len(prefix):]).parts)
        if target in selected:
            raise ValueError(f"Duplicate archive path: {name}")
        selected.add(target)
        if member.isdir():
            target.mkdir(parents=True, exist_ok=True)
            continue
        if member.islnk():
            if not member.linkname.removeprefix("./").startswith(prefix):
                raise ValueError(f"External hardlink: {name}")
        elif not member.isfile():
            raise ValueError(f"Unexpected archive entry: {name}")
        target.parent.mkdir(parents=True, exist_ok=True)
        with archive.extractfile(member) as source, target.open("wb") as output:
            shutil.copyfileobj(source, output)
    if not selected:
        raise ValueError(f"Missing archive tree: {prefix}")

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

def verified_git_manifest(manifest):
    lines = manifest.read_text(encoding="utf-8").splitlines()
    if [line for line in lines if line.startswith("termux\tgit\t")] != [
            f"termux\tgit\t{GIT_VERSION}\t{GIT_DEB[1]}"]:
        raise ValueError("Git package differs from pinned distributor manifest")
    if [line for line in lines if line.startswith("app-commit\t")] != [
            f"app-commit\t{VSCODROID_COMMIT}"]:
        raise ValueError("Distributor commit differs from pinned provenance")
    if any(line.startswith("# NOTE git was resolved twice") for line in lines):
        raise ValueError("Ambiguous Git resolution in distributor manifest")


def patch_git_shell(binary):
    original = b"/data/data/com.termux/files/usr/bin/sh\0"
    replacement = b"/system/bin/sh".ljust(len(original), b"\0")
    if binary.count(original) != 1:
        raise ValueError("Unexpected compiled Git shell path")
    return binary.replace(original, replacement)


def git_binaries(native, work, manifest):
    verified_git_manifest(manifest)
    deb = fetch(*GIT_DEB, work)
    extracted = work / "git-deb"
    subprocess.run(["dpkg-deb", "--extract", str(deb), str(extracted)], check=True)
    prefix = extracted / "data/data/com.termux/files/usr"
    sources = {"libcodex_git.so": "bin/git",
               "libcodex_git_remote_http.so": "libexec/git-core/git-remote-https"}
    result = {}
    for name, relative_path in sources.items():
        # The package may contain an absolute Termux symlink. Resolve it
        # against the extracted package, never against the CI host.
        source = prefix / relative_path
        if source.is_symlink():
            link = source.readlink()
            if link.is_absolute():
                source = extracted / str(link).lstrip("/")
        source = source.resolve(strict=True)
        if not source.is_relative_to(prefix.resolve()) or not source.is_file():
            raise ValueError(f"Git package executable escaped package: {source}")
        target = native / name
        target.write_bytes(patch_git_shell(source.read_bytes()))
        result[name] = digest(target)
    return result


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
        archive.add(manifest, arcname="VSCodroid-build-manifest.txt")
        archive.add(pathlib.Path(__file__).resolve(), arcname="prepare_prebuilt_developer_runtime.py")
    temporary.replace(output)
    return digest(output)


def git_payload(apk, abi, native, data):
    names = set(apk.namelist())
    libroot = data / "git/lib"
    libroot.mkdir(parents=True, exist_ok=True)
    queue = [native / "libcodex_git.so", native / "libcodex_git_remote_http.so"]
    seen = set()
    while queue:
        path = queue.pop()
        if path in seen:
            continue
        seen.add(path)
        for dep in sorted(needed(path, abi, path.parent == native)):
            if dep in SYSTEM or (native / dep).is_file():
                continue
            if not re.fullmatch(r"lib[A-Za-z0-9_+.-]+\.so(?:\.[A-Za-z0-9.]+)?", dep):
                raise ValueError(f"Invalid dependency name: {dep}")
            source = "assets/usr/lib/" + dep
            if source not in names:
                raise ValueError(f"Missing Git dependency: {dep}")
            target = libroot / dep
            if not target.exists():
                copy_zip(apk, source, target)
            queue.append(target)
    prefix = "assets/usr/share/git-core/templates/"
    for name in sorted(names):
        if name.startswith(prefix) and not name.endswith("/"):
            copy_zip(apk, name, (data / "git/templates").joinpath(*relative(name[len(prefix):]).parts))
        if name.startswith("assets/usr/share/doc/") and not name.endswith("/"):
            if re.match(r"(?i)^(copyright|copying|licen[cs]e|notice)", name.rsplit("/", 1)[-1]):
                copy_zip(apk, name, (data / "licenses/git-distribution").joinpath(
                    *relative(name[len("assets/usr/share/doc/"):]).parts))
    if not (data / "git/templates").is_dir():
        raise ValueError("Git templates missing")
    if not any((data / "licenses/git-distribution/git").glob("*")):
        raise ValueError("Git upstream notice missing")

def prepare(abi, native, assets, work, git_source_output=None):
    data = work / "data"
    data.mkdir()
    assets.mkdir(parents=True, exist_ok=True)
    filename, sha = DSH[abi]
    dsh_url = "https://github.com/Windmill12/dsh-for-android/releases/download/v0.2.0/" + filename
    with zipfile.ZipFile(fetch(dsh_url, sha, work)) as apk:
        # The prebuilt npm launcher locates its sibling libnode.so.
        for name in ("libnode.so", "libnpm.so", "libpython3.so"):
            copy_zip(apk, f"lib/{abi}/{name}", native / name)
        if not (native / "libc++_shared.so").is_file():
            copy_zip(apk, f"lib/{abi}/libc++_shared.so", native / "libc++_shared.so")
        with apk.open("assets/python-runtime.pkg") as raw, tarfile.open(fileobj=raw, mode="r:gz") as tar:
            copy_tar_tree(tar, f"{abi}/lib/python3.13", data / "lib/python3.13")
        with apk.open("assets/dsh-runtime.pkg") as raw, tarfile.open(fileobj=raw, mode="r:gz") as tar:
            copy_tar_tree(tar, "node_modules/npm", data / "npm")
    wheel = fetch(*PIP_WHEEL, work)
    if wheel.name != f"pip-{PIP}-py3-none-any.whl":
        raise ValueError("pip wheel filename/version mismatch")
    with zipfile.ZipFile(wheel) as archive:
        for info in archive.infolist():
            if not info.is_dir():
                copy_zip(archive, info.filename, (data / "lib/python3.13/site-packages").joinpath(*relative(info.filename).parts))
    curl_apk = work / "curl.apk"
    with lzma.open(fetch(*CURL, work)) as source, curl_apk.open("wb") as output:
        shutil.copyfileobj(source, output)
    with zipfile.ZipFile(curl_apk) as apk:
        copy_zip(apk, f"lib/{abi}/libcurl.so", native / "libcodex_curl.so")
    provenance = {"abi": abi, "dsh": {"url": dsh_url, "sha256": sha},
                  "curl": {"url": CURL[0], "sha256": CURL[1]},
                  "pip": {"version": PIP, "url": PIP_WHEEL[0], "sha256": PIP_WHEEL[1]},
                  "gitHttps": abi == "arm64-v8a"}
    if abi == "arm64-v8a":
        if git_source_output is None:
            raise ValueError("ARM64 Git corresponding source output is required")
        manifest = fetch(*GIT_MANIFEST, work)
        binaries = git_binaries(native, work, manifest)
        with zipfile.ZipFile(fetch(*GIT, work)) as apk:
            git_payload(apk, abi, native, data)
        shutil.copyfile(manifest, data / "git-distributor-manifest.txt")
        source_archive_sha = git_corresponding_source(work, manifest, git_source_output)
        provenance["git"] = {
            "version": GIT_VERSION, "packageUrl": GIT_DEB[0], "packageSha256": GIT_DEB[1],
            "url": GIT[0], "sha256": GIT[1], "manifest": GIT_MANIFEST[1],
            "termuxRecipeCommit": TERMUX_RECIPE_COMMIT, "upstreamSourceSha256": GIT_SOURCE[1],
            "sourceArchiveSha256": source_archive_sha, "binaries": binaries,
        }
        provenance["gitCorrespondingSourceVerified"] = True
    for path in [native / x for x in ("libnode.so", "libnpm.so", "libpython3.so", "libcodex_curl.so")]:
        for dep in needed(path, abi, True) - SYSTEM:
            if not (native / dep).is_file():
                raise ValueError(f"Unresolved runtime dependency {dep}: {path}")
    for path in (data / "lib/python3.13").rglob("*.so"):
        for dep in needed(path, abi) - SYSTEM:
            if not (native / dep).is_file():
                raise ValueError(f"Unresolved Python extension dependency {dep}: {path}")
    for path in ("lib/python3.13/encodings/__init__.py",
                 "lib/python3.13/site-packages/pip/__main__.py", "npm/bin/npm-cli.js"):
        if not (data / path).is_file():
            raise ValueError(f"Incomplete precompiled runtime: {path}")
    out = assets / "development-runtime.zip"
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for path in sorted(data.rglob("*")):
            if path.is_file():
                archive.write(path, path.relative_to(data).as_posix())
    (assets / "development-runtime.sha256").write_text(digest(out) + "\n")
    (assets / "development-runtime.version").write_text(f"AndroidDSH 0.2.0 / curl 8.18.0 / pip {PIP}\n")
    (assets / "development-runtime.provenance.json").write_text(json.dumps(provenance, indent=2) + "\n")
    print(json.dumps(provenance, indent=2))
    print(f"Runtime data archive: {out.stat().st_size} bytes")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("abi", choices=sorted(DSH))
    parser.add_argument("native", type=pathlib.Path)
    parser.add_argument("assets", type=pathlib.Path)
    parser.add_argument("--git-source-output", type=pathlib.Path)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="prebuilt-developer-") as tmp:
        prepare(args.abi, args.native, args.assets, pathlib.Path(tmp), args.git_source_output)
