#!/usr/bin/env python3
"""Check Android's protocol UI catalog against the official Codex contract and UI policy."""
from __future__ import annotations
import argparse
import json
import re
from pathlib import Path
import sys

SURFACES = {
    "Direct": "直接操作", "Contextual": "上下文操作",
    "Advanced": "高级按需", "Internal": "后台内部",
    "Unsupported": "平台不适用",
}
MATRIX_ROW = re.compile(
    r'ProtocolSurface\(\s*"([^"]+)"\s*,\s*ApiExposure\.(\w+)\s*,')
OFFICIAL = re.compile(r'=>\s*"([^"]+/[^"]+)"\s*\{')


def verify(matrix_source: str, policy_source: str, official_source: str) -> list[str]:
    errors: list[str] = []
    declarations = list(MATRIX_ROW.finditer(matrix_source))
    matrix = {match.group(1): match.group(2) for match in declarations}
    if len(matrix) != len(declarations):
        errors.append("UiProtocolMatrix 包含重复方法名")
    try:
        policy = json.loads(policy_source)["methods"]
    except (ValueError, KeyError, TypeError) as exc:
        return errors + ["UI 分层策略无法解析：" + str(exc)]
    if not isinstance(policy, dict):
        return errors + ["UI 分层策略 methods 必须是对象"]
    official = set(OFFICIAL.findall(official_source))
    if len(official) < 100:
        errors.append("官方协议方法异常少，可能读取了错误的上游文件")
    for name in sorted(official - matrix.keys()):
        errors.append("Android 协议目录缺少：" + name)
    for name in sorted(matrix.keys() - official):
        errors.append("Android 协议目录记录了上游不存在的方法：" + name)
    for name in sorted(policy.keys() - matrix.keys()):
        errors.append("UI 分层策略缺少 Kotlin 目录映射：" + name)
    for name in sorted(matrix.keys() - policy.keys()):
        errors.append("Kotlin 目录缺少 UI 分层策略映射：" + name)
    for name, exposure in matrix.items():
        expected = SURFACES.get(exposure)
        if expected is None:
            errors.append("Android 协议目录使用未知层级：" + exposure)
        elif name in policy and policy[name].get("surface") != expected:
            errors.append(
                "UI 分层不一致：" + name + "；Kotlin=" + expected +
                "；策略=" + str(policy[name].get("surface")))
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--matrix", type=Path, required=True)
    parser.add_argument("--policy", type=Path, required=True)
    parser.add_argument("--protocol", type=Path, required=True)
    args = parser.parse_args()
    errors = verify(
        args.matrix.read_text(encoding="utf-8"),
        args.policy.read_text(encoding="utf-8"),
        args.protocol.read_text(encoding="utf-8"),
    )
    if errors:
        for error in errors:
            print("Android 协议目录核验失败：" + error, file=sys.stderr)
        return 1
    print("Android 协议目录、UI 分层策略与上游官方方法清单一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
