#!/usr/bin/env python3
"""Install the exact Linux host and Android SDK distributions used by 111."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
from xml.sax.saxutils import escape, quoteattr

LOCK = json.loads(Path(__file__).with_name("platform-toolchain.json").read_text())
ANDROID = LOCK["android"]
HOST = LOCK["host"]
PROFILES = {
    "build": ("cmdline", "compile-platform", "compile-tools", "ndk"),
    "native": ("ndk",),
    "v8": ("cmdline", "v8-platform", "v8-tools", "ndk"),
    "emulator": ("cmdline", "adb", "emulator", "image"),
    "developer-android": ("cmdline", "adb", "compile-platform", "compile-tools", "v8-platform", "v8-tools", "ndk"),
    "developer-emulator": ("cmdline", "adb", "emulator", "image", "emulator-platform"),
}
NAMESPACES = {
    "common": "http://schemas.android.com/repository/android/common/02",
    "generic": "http://schemas.android.com/repository/android/generic/02",
    "sdk": "http://schemas.android.com/sdk/android/repo/repository2/03",
    "sys-img": "http://schemas.android.com/sdk/android/repo/sys-img2/03",
}


def recipe(text, *, host=(), android=()):
    """Record only the distributions that actually contribute to this build."""
    if not text:
        return None
    lock = json.loads(text)
    return {kind: {name: lock[kind][name] for name in names}
            for kind, names in (("host", host), ("android", android)) if names}


def android_path(sdk, name):
    return sdk.joinpath(*ANDROID[name]["package"].split(";"))


def read_properties(file):
    if not file.is_file():
        return {}
    return dict(tuple(part.strip() for part in line.split("=", 1))
                for line in file.read_text().splitlines() if "=" in line and not line.startswith("#"))


def android_available(path, record):
    receipt = path / ".111-distribution-sha256"
    return ((path / record["probe"]).is_file() and
            (path / "package.xml").is_file() and
            read_properties(path / "source.properties").get("Pkg.Revision") == record["version"] and
            receipt.is_file() and receipt.read_text().strip() == record["sha256"])


def download_archive(record, target):
    subprocess.run(["curl", "--fail", "--silent", "--show-error", "--location", "--retry", "3",
                    "--proto", "=https", "--proto-redir", "=https", record["url"], "-o", str(target)],
                   check=True)
    with target.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    if digest != record["sha256"]:
        raise ValueError(f"Distribution SHA-256 mismatch: {record['url']}")


def sdk_metadata(record):
    namespaces = " ".join(f"xmlns:{key}={quoteattr(value)}" for key, value in NAMESPACES.items())
    revision = "".join(f"<{tag}>{number}</{tag}>" for tag, number in
                       zip(("major", "minor", "micro"), record["version"].split(".")))
    return (
        f"<common:repository {namespaces}>\n"
        f"  <localPackage path={quoteattr(record['package'])} obsolete=\"false\">\n"
        f"    {record['details']}\n"
        f"    <revision>{revision}</revision>\n"
        f"    <display-name>{escape(record['title'])}</display-name>\n"
        "  </localPackage>\n"
        "</common:repository>\n"
    )


def install_android(sdk, name):
    record = ANDROID[name]
    destination = android_path(sdk, name)
    if android_available(destination, record):
        return destination
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=destination.parent) as work:
        folder = Path(work)
        archive = folder / "distribution.zip"
        download_archive(record, archive)
        subprocess.run(["unzip", "-q", str(archive), "-d", work], check=True)
        source = folder / record["root"]
        (source / "package.xml").write_text(sdk_metadata(record))
        (source / ".111-distribution-sha256").write_text(record["sha256"] + "\n")
        if not android_available(source, record):
            raise ValueError(f"Unexpected Android SDK distribution layout: {record['package']}")
        replace_directory(source, destination)
    return destination


def replace_directory(source, destination):
    # The operator controls the SDK root. Move its previous package aside only
    # after the replacement has been verified and can be renamed on the same volume.
    retired = source.parent / "previous"
    if destination.is_symlink() or destination.exists():
        destination.rename(retired)
    try:
        source.rename(destination)
    except OSError:
        if retired.exists() or retired.is_symlink():
            retired.rename(destination)
        raise


def install_host(root, name):
    record = HOST[name]
    destination = root / name
    receipt = destination / ".111-distribution-sha256"
    if (destination / record["probe"]).is_file() and receipt.is_file() and receipt.read_text().strip() == record["sha256"]:
        return destination
    root.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=root) as work:
        folder = Path(work)
        archive = folder / "distribution.tar.gz"
        download_archive(record, archive)
        extracted = folder / "archive"
        extracted.mkdir()
        subprocess.run(["tar", "-xf", str(archive), "-C", str(extracted)], check=True)
        source, = extracted.iterdir()
        if not (source / record["probe"]).is_file():
            raise ValueError(f"Unexpected host distribution layout: {name}")
        (source / ".111-distribution-sha256").write_text(record["sha256"] + "\n")
        replace_directory(source, destination)
    return destination


def publish_host(name, directory):
    if "GITHUB_PATH" in os.environ:
        with open(os.environ["GITHUB_PATH"], "a") as stream:
            stream.write(str(directory / "bin") + "\n")
    if name == "jdk" and "GITHUB_ENV" in os.environ:
        with open(os.environ["GITHUB_ENV"], "a") as stream:
            stream.write(f"JAVA_HOME={directory}\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for command, names in (("host", HOST), ("sdk", PROFILES)):
        sub = commands.add_parser(command)
        sub.add_argument("root", type=Path)
        sub.add_argument("names", nargs="+", choices=names)
    args = parser.parse_args()
    root = args.root.resolve()
    if args.command == "host":
        for name in args.names:
            directory = install_host(root, name)
            publish_host(name, directory)
            print(f"{name} {HOST[name]['version']} {directory}")
    else:
        for name in dict.fromkeys(package for profile in args.names for package in PROFILES[profile]):
            directory = install_android(root, name)
            print(f"{ANDROID[name]['package']} {ANDROID[name]['version']} {directory}")


if __name__ == "__main__":
    main()
