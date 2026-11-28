#!/usr/bin/env python3
"""Make pinned rusty_v8's Android cross-builds use the host toolchain.

Chromium host tools are compiled and run on GitHub Actions Linux; the Android
target uses the NDK sysroot. Downloading a Debian image for Linux host tools is
unnecessary and fails in restricted CI networks. Other targets are untouched.
"""
import argparse
from pathlib import Path

OLD = '''    if target_arch == "x86_64" {
      maybe_install_sysroot("amd64");
    }
    gn_args.push(format!(r#"v8_target_cpu="{arch}""#).to_string());'''
NEW = '''    // Android uses the NDK target sysroot and runner Linux host tools.
    gn_args.push(format!(r#"v8_target_cpu="{arch}""#).to_string());'''

OLD_SYSROOT = '''    gn_args.push("use_sysroot=true".to_string());

    // NDK 23 and above removes libgcc entirely.'''
NEW_SYSROOT = '''    gn_args.push("use_sysroot=false".to_string());

        // NDK 23 and above removes libgcc entirely.'''


OLD_BINDGEN = '''  let bindings = bindgen::Builder::default()
    .header("src/binding.hpp")
    .parse_callbacks(Box::new(bindgen::CargoCallbacks::new()))
    .clang_args(clang_args)
    .clang_args(filtered_args)'''
NEW_BINDGEN = '''  // Only the final V8 binding targets Android; GN host bindgen keeps Linux flags.
  let android_args = if target_os == "android" {
    let target = env::var("RUSTY_V8_ANDROID_BINDGEN_TARGET")
      .expect("Missing versioned Android bindgen target");
    let sysroot = env::var("RUSTY_V8_ANDROID_BINDGEN_SYSROOT")
      .expect("Missing Android bindgen sysroot");
    vec![format!("--target={target}"), format!("--sysroot={sysroot}")]
  } else {
    Vec::new()
  };

  let bindings = bindgen::Builder::default()
    .header("src/binding.hpp")
    .parse_callbacks(Box::new(bindgen::CargoCallbacks::new()))
    .clang_args(clang_args)
    .clang_args(filtered_args)
    .clang_args(android_args)'''


def adapt(source: str) -> str:
    if all(anchor in source for anchor in (NEW, NEW_SYSROOT, NEW_BINDGEN)):
        return source
    for before, after, label in (
        (OLD, NEW, "Android host sysroot installer"),
        (OLD_SYSROOT, NEW_SYSROOT, "Android GN sysroot flag"),
        (OLD_BINDGEN, NEW_BINDGEN, "Android final binding arguments"),
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
    print("Android V8 host sysroot adaptation verified")


if __name__ == "__main__":
    main()
