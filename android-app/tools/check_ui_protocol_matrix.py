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


PAGE_LINE = re.compile(r'^\s*Page\("([^"]+)"', re.MULTILINE)
FEATURE_LINE = re.compile(r'\bUiFeature\("([^"]+)"')
UI_CALL = re.compile(r'\bvm\.(?:rpc|refresh)\(\s*"[^"]+"\s*,\s*"([^"]+)"', re.DOTALL)
NON_UI_EXPOSURES = {"后台内部", "平台不适用"}


def verify_ui_entrypoints(page_source: str, feature_source: str,
                          policy_source: str, sources: Path | None = None) -> list[str]:
    """Fail closed when internal/Host/transport methods gain a normal UI entry.

    UI entrypoints are explicitly declared. An upstream method never becomes
    an Android navigation action merely because it exists in the protocol.
    """
    errors: list[str] = []
    policy = json.loads(policy_source)["methods"]
    pages = PAGE_LINE.findall(page_source)
    features = FEATURE_LINE.findall(feature_source)
    if not pages or not features:
        return ["没有找到可校验的页面/功能目录"]
    if len(set(pages)) != len(pages):
        errors.append("页面目录中存在重复路由")
    if len(set(features)) != len(features):
        errors.append("功能目录中存在重复路由")
    if set(pages) != set(features) - {"update"}:
        errors.append("可导航页面与功能目录不一致：" +
                      str(sorted(set(pages) ^ (set(features) - {"update"}))))
    for line in page_source.splitlines():
        if not line.strip().startswith('Page("'):
            continue
        quoted = re.findall(r'"([^"]+)"', line)
        route = quoted[0]
        for method in quoted[1:]:
            entry = policy.get(method)
            if entry and entry["surface"] in NON_UI_EXPOSURES:
                errors.append(f"后台接口被放入 UI 页面：{route} -> {method}")
    if sources is not None:
        for path in sources.rglob("*.kt"):
            content = path.read_text(encoding="utf-8")
            if "@Composable" not in content:
                continue
            for match in UI_CALL.finditer(content):
                method = match.group(1)
                entry = policy.get(method)
                if entry is None and "/" in method:
                    errors.append(f"UI 直接调用未登记协议：{path.name} -> {method}")
                elif entry is not None and entry["surface"] in NON_UI_EXPOSURES:
                    errors.append(f"UI 直接发起后台协议：{path.name} -> {method}")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--matrix", type=Path, required=True)
    parser.add_argument("--policy", type=Path, required=True)
    parser.add_argument("--protocol", type=Path, required=True)
    parser.add_argument("--pages", type=Path)
    parser.add_argument("--features", type=Path)
    parser.add_argument("--sources", type=Path)
    args = parser.parse_args()
    try:
        errors = verify(
            args.matrix.read_text(encoding="utf-8"),
            args.policy.read_text(encoding="utf-8"),
            args.protocol.read_text(encoding="utf-8"),
        )
        if args.pages is not None and args.features is not None:
            errors += verify_ui_entrypoints(
                args.pages.read_text(encoding="utf-8"),
                args.features.read_text(encoding="utf-8"),
                args.policy.read_text(encoding="utf-8"),
                args.sources)
    except OSError as exc:
        parser.error(str(exc))
    if errors:
        for error in errors:
            print("Android 协议目录核验失败：" + error, file=sys.stderr)
        return 1
    print("Android 协议与 UI 入口分类核验一致；后台接口不会成为用户操作入口")
    return 0


if __name__ == "__main__":
    sys.exit(main())
