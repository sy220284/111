#!/usr/bin/env python3
"""Decide whether a verified 111 Android source revision needs a new APK release."""
import argparse
import subprocess
import sys

from select_native_artifacts import ArtifactSelector, SelectionError

from ci_policy import matches, maintenance_only


def changes_apk_inputs(paths: list[str]) -> bool:
    return any(matches(path, 'release') for path in paths)


def should_release(changed_paths: list[str], base: str, head: str,
                   selector: ArtifactSelector) -> bool:
    if maintenance_only(changed_paths):
        return False
    if changes_apk_inputs(changed_paths):
        return True
    # Native recipe changes need release; workflow triggers, test files and
    # upstream SDK/docs changes do not. The signature includes Cargo inputs.
    return selector.signature(base) != selector.signature(head)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", required=True, help="previous published Git tag")
    parser.add_argument("--head", required=True, help="current verified main commit")
    args = parser.parse_args()
    try:
        diff = subprocess.run(
            ["git", "diff", "--name-only", args.base, args.head],
            capture_output=True, text=True, check=True,
        )
        files = [p for p in diff.stdout.splitlines() if p]
        changed = should_release(files, args.base, args.head, ArtifactSelector())
    except (subprocess.CalledProcessError, SelectionError, OSError, ValueError) as exc:
        print(f"发布输入核验失败：{exc}", file=sys.stderr)
        return 1
    if changed:
        print("发现手机 APK、发布资产或 ARM64 原生编译输入变化，允许发布")
        return 0
    print("仅有文档、测试、上游非 Android 文件或 CI 调度变化，跳过重复 APK")
    return 3


if __name__ == "__main__":
    sys.exit(main())
