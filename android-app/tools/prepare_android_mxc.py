#!/usr/bin/env python3
"""Prepare pinned upstream MXC source to compile for Android, without changing the snapshot.

MXC has no Android sandbox backend. dispatch.rs correctly rejects unsupported OSes,
but three audit calls refer to a module gated to desktop OSes, causing E0433.
Match the same platform gate as mxc_engine/mod.rs for these calls.

This is an explicit platform compatibility fix, not a substituted sandbox engine.
Actual Android command execution is separately validated in device tests.
"""
from pathlib import Path
import argparse
import sys

UPSTREAM_COMMIT_PREFIX = "c662018"
OS_GUARD = '#[cfg(any(target_os = "windows", target_os = "linux", target_os = "macos"))]'
AUDIT_CALL = "crate::mxc_engine::run::log_policy_hash(request, logger);"


def find_source(cargo_home: Path) -> Path:
    root = cargo_home / "git" / "checkouts"
    expected = list(root.glob(f"mxc-*/{UPSTREAM_COMMIT_PREFIX}/src/mxc-sdk/src/core/mxc_engine/dispatch.rs"))
    if len(expected) != 1:
        raise RuntimeError(f"MXC checkout must be uniquely pinned to {UPSTREAM_COMMIT_PREFIX}, found {len(expected)}")
    return expected[0]


def fix(source: Path) -> None:
    contents = source.read_text(encoding="utf-8")
    if contents.count(AUDIT_CALL) != 3:
        raise RuntimeError("MXC SDK source changed; expected exactly three policy audit call sites")
    lines = contents.splitlines(keepends=True)
    fixed = []
    modified = 0
    for line in lines:
        if line.strip() == AUDIT_CALL:
            prefix = line[:len(line) - len(line.lstrip())]
            if fixed and fixed[-1].strip() == OS_GUARD:
                fixed.append(line)
                continue
            fixed.append(prefix + OS_GUARD + "\n")
            modified += 1
        fixed.append(line)
    if modified not in (0, 3):
        raise RuntimeError(f"Unexpected number of MXC Android target gate insertions: {modified}")
    source.write_text("".join(fixed), encoding="utf-8")
    print(f"MXC platform guard: checked 3 call sites, added {modified} gates in {source}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--cargo-home", required=True, type=Path)
    args = parser.parse_args()
    try:
        fix(find_source(args.cargo_home))
    except (RuntimeError, OSError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
