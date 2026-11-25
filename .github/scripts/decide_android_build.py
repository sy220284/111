#!/usr/bin/env python3
"""Avoid a second full APK build when the same main push triggers native CI."""
import argparse
import re
import subprocess
from pathlib import Path

# Keep in sync with build-native-codex.yml's main-branch push path filter.
NATIVE_PREFIXES = (
    "upstream/codex/codex-rs/",
    "upstream/codex/.cargo/",
)
NATIVE_FILES = frozenset({
    ".github/workflows/build-native-codex.yml",
    ".github/scripts/select_native_artifacts.py",
    "android-app/tools/prepare_android_mxc.py",
    "android-app/tools/prepare_android_code_mode.py",
    "android-app/tools/prepare_android_v8.sh",
    "android-app/tools/adapt_android_v8_build.py",
    "android-app/tools/build_android_git.sh",
})


def native_will_run(changed_paths: list[str]) -> bool:
    return any(path in NATIVE_FILES or path.startswith(NATIVE_PREFIXES)
               for path in changed_paths)


def changed_paths(before: str, after: str) -> list[str]:
    if not re.fullmatch(r"[0-9a-f]{40}", before) or not re.fullmatch(r"[0-9a-f]{40}", after):
        raise ValueError("push 前后提交必须是完整的 40 位 SHA")
    if before == "0" * 40:
        # New-branch creation has no prior tree; preserve a normal APK build.
        return []
    result = subprocess.run(
        ["git", "diff", "--no-renames", "--name-only", "-z", before, after, "--"],
        check=True, capture_output=True,
    )
    return [part.decode("utf-8") for part in result.stdout.split(b"\0") if part]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--before", required=True)
    parser.add_argument("--after", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    paths = changed_paths(args.before, args.after)
    defer = native_will_run(paths)
    # GitHub always launches the native workflow for any of these inputs.
    # Defer the APK build to the native workflow_run completion for this SHA.
    with args.output.open("a", encoding="utf-8") as stream:
        stream.write(f"should_build={'false' if defer else 'true'}\n")
    print("本次 push 同时触发原生内核检查；APK 等待原生工作流完成"
          if defer else "本次 push 直接进行 Android APK 验收")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
