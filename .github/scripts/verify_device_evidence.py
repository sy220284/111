#!/usr/bin/env python3
"""Strict device harness evidence contract; no skipped checks count as passing."""
import hashlib
import json
import sys
from pathlib import Path
REQUIRED = {"core_start", "git_https", "curl_https", "python_pip", "node_npm", "account", "remote_workspace", "mcp", "permission_deny", "permission_revoke", "crash_recovery", "upgrade_data", "self_update"}

def verify(report, version, sha):
    if report.get("version") != version or report.get("apk_sha256") != sha:
        raise ValueError("设备证据与正式 APK 不一致")
    checks = report.get("checks", {})
    if not REQUIRED <= checks.keys() or any(checks[k] != "passed" for k in REQUIRED):
        raise ValueError("设备验收不完整或失败")
    return True

if __name__ == "__main__":
    report, version, apk = sys.argv[1:]
    with open(apk, "rb") as f:
        sha = hashlib.file_digest(f, "sha256").hexdigest()
    verify(json.loads(Path(report).read_text()), version, sha)
