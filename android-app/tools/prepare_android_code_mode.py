#!/usr/bin/env python3
"""Adapt upstream host discovery to Android's APK native-library executable layout."""
import argparse
from pathlib import Path

ANCHOR = """        if let Some(executable_dir) = executable_dir {
            let executable = executable_dir.join(CODE_MODE_HOST_EXECUTABLE_NAME);"""
ANDROID_DISCOVERY = """        #[cfg(target_os = "android")]
        if let Some(executable_dir) = executable_dir.as_ref() {
            // Android extracts executable ELF helpers from jniLibs alongside
            // libcodex_server.so, which is executable on Android API 29+.
            return executable_dir
                .join("libcodex_code_mode_host.so")
                .into_path_buf();
        }
"""

def adapt(source: str) -> str:
    if ANDROID_DISCOVERY in source:
        return source
    if source.count(ANCHOR) != 1:
        raise ValueError("Codex upstream host discovery changed; review Android adaptation")
    return source.replace(ANCHOR, ANDROID_DISCOVERY + ANCHOR, 1)

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("--check", action="store_true", help="Validate without writing")
    args = parser.parse_args()
    original = args.source.read_text(encoding="utf-8")
    updated = adapt(original)
    if not args.check and updated != original:
        args.source.write_text(updated, encoding="utf-8")
    print("Android Code Mode Host path validated: libcodex_code_mode_host.so")

if __name__ == "__main__":
    main()
