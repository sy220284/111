#!/usr/bin/env python3
"""Make pinned rusty_v8's Android x86_64 cross-build use the host toolchain.

Chromium host tools are compiled and run on GitHub Actions Linux; the Android
target uses the NDK sysroot. Downloading a Debian image for Linux host tools is
unnecessary and fails in restricted CI networks. Other targets are untouched.
"""
import argparse
from pathlib import Path

OLD = '''        if target_arch == "x86_64" {
          maybe_install_sysroot("amd64");
        }
        gn_args.push(format!(r#"v8_target_cpu="{arch}""#).to_string());'''
NEW = '''        // Android x86_64 uses NDK headers for target; host tools use runner Linux.
        // The downloadable Debian sysroot is unnecessary for this cross-build.
        gn_args.push(format!(r#"v8_target_cpu="{arch}""#).to_string());'''

OLD_SYSROOT = '''        gn_args.push("use_sysroot=true".to_string());

        // NDK 23 and above removes libgcc entirely.'''
NEW_SYSROOT = '''        gn_args.push("use_sysroot=false".to_string());

        // NDK 23 and above removes libgcc entirely.'''


def adapt(source: str) -> str:
    if NEW in source and NEW_SYSROOT in source:
        return source
    for before, after, label in (
        (OLD, NEW, "Android x86_64 sysroot installer"),
        (OLD_SYSROOT, NEW_SYSROOT, "Android GN sysroot flag"),
    ):
        if source.count(before) != 1:
            raise ValueError(f"Upstream rusty_v8 changed {label}; inspect pinned source")
        source = source.replace(before, after, 1)
    return source


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("file", type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    original = args.file.read_text(encoding="utf-8")
    adapted = adapt(original)
    if not args.check and adapted != original:
        args.file.write_text(adapted, encoding="utf-8")
    print("Android x86_64 V8 host sysroot adaptation verified")


if __name__ == "__main__":
    main()
