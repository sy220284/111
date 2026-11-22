#!/usr/bin/env python3
"""Verify the Android protocol catalog against the pinned Rust contract.

Method names, direction, ownership, UI exposure and method-level experimental
metadata must agree across Rust, Kotlin and ui_surface_policy.json.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys

from protocol_contract import parse_contracts

SURFACES = {
    "Direct": "直接操作",
    "Contextual": "上下文操作",
    "Advanced": "高级按需",
    "Internal": "后台内部",
    "Unsupported": "平台不适用",
}
MATRIX_ROW = re.compile(
    r'ProtocolSurface\(\s*"([^"]+)"\s*,\s*ApiExposure\.(\w+)\s*,\s*'
    r'"([^"]+)"\s*,\s*(true|false)\s*\)'
)


def verify(matrix_source: str, policy_source: str, official_source: str) -> list[str]:
    errors: list[str] = []
    declarations = list(MATRIX_ROW.finditer(matrix_source))
    matrix = {
        match.group(1): {
            "surface": SURFACES.get(match.group(2)),
            "exposure": match.group(2),
            "owner": match.group(3),
            "experimental": match.group(4) == "true",
        }
        for match in declarations
    }
    if len(matrix) != len(declarations):
        errors.append("UiProtocolMatrix 包含重复方法名")
    try:
        document = json.loads(policy_source)
        policy = document["methods"]
    except (ValueError, KeyError, TypeError) as exc:
        return errors + ["UI 分层策略无法解析：" + str(exc)]
    if not isinstance(policy, dict):
        return errors + ["UI 分层策略 methods 必须是对象"]
    try:
        official = parse_contracts(official_source)
    except ValueError as exc:
        return errors + ["官方协议解析失败：" + str(exc)]
    for name in sorted(official.keys() - matrix.keys()):
        errors.append("Android 协议目录缺少：" + name)
    for name in sorted(matrix.keys() - official.keys()):
        errors.append("Android 协议目录记录了上游不存在的方法：" + name)
    for name in sorted(policy.keys() - matrix.keys()):
        errors.append("UI 分层策略缺少 Kotlin 目录映射：" + name)
    for name in sorted(matrix.keys() - policy.keys()):
        errors.append("Kotlin 目录缺少 UI 分层策略映射：" + name)
    for name, entry in matrix.items():
        exposure = entry["exposure"]
        if exposure not in SURFACES:
            errors.append("Android 协议目录使用未知层级：" + exposure)
            continue
        if name not in official or name not in policy:
            continue
        p = policy[name]
        if not isinstance(p, dict):
            errors.append("UI 分层策略条目必须为对象：" + name)
            continue
        if entry["surface"] != p.get("surface"):
            errors.append(f"UI 分层不一致：{name}；Kotlin={entry['surface']}；策略={p.get('surface')}")
        if entry["owner"] != p.get("owner"):
            errors.append(f"功能归属不一致：{name}；Kotlin={entry['owner']}；策略={p.get('owner')}")
        exp = official[name].experimental
        if entry["experimental"] != exp or (p.get("flag") == "实验") != exp:
            errors.append(
                f"实验标记不一致：{name}；官方={exp}；Kotlin={entry['experimental']}；"
                f"策略={p.get('flag')}"
            )
        if official[name].direction == "host_request" and exposure != "Internal":
            errors.append(f"Host 主动请求必须由后台归口处理：{name}")
        if exposure == "Unsupported" and not name.startswith("windowsSandbox/"):
            errors.append(f"不适用平台分类需要逐项核实：{name}")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--matrix", type=Path, required=True)
    parser.add_argument("--policy", type=Path, required=True)
    parser.add_argument("--protocol", type=Path, required=True)
    args = parser.parse_args()
    try:
        errors = verify(
            args.matrix.read_text(encoding="utf-8"),
            args.policy.read_text(encoding="utf-8"),
            args.protocol.read_text(encoding="utf-8"),
        )
    except OSError as exc:
        parser.error(str(exc))
    if errors:
        for error in errors:
            print("Android 协议目录核验失败：" + error, file=sys.stderr)
        return 1
    print("Android 请求方向、UI 分类、归属与官方方法级实验标注核验一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
