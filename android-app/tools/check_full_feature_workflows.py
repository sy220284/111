#!/usr/bin/env python3
"""Check all current user functions against source, regression declarations and Rust RPCs.

This is a reproducible evidence index, NOT a substitute for device or real-core
execution. Actual screenshots, RPC responses and restore scenarios are graded
separately by the CI jobs that produce those artifacts.
"""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

from protocol_contract import parse_contracts

ROW = re.compile(r"^\|\s*(\d{2})\s*\|\s*`([a-z]+)`\s*\|\s*(P[012])\s*\|\s*([^|]*)\|\s*([^|]*)\|\s*([^|]*)\|", re.M)
PAGE = re.compile(r'\bPage\("([a-z]+)"')
FEATURE = re.compile(r'\bUiFeature\("([a-z]+)"')
SCREEN = re.compile(r'([a-z][a-z0-9-]+)-android35\.png')
TEST_METHOD = re.compile(r'@Test(?:\([^)]*\))?\s+fun\s+([A-Za-z][A-Za-z0-9_]*)\b')
TEST_CLASS = re.compile(r'\bclass\s+([A-Za-z][A-Za-z0-9_]*Test)\b')
SOURCE_NAME = re.compile(r'\b([A-Z][A-Za-z0-9_]*)\.kt\b')


def audit(repository: Path, expected: int = 31) -> dict:
    source = repository / "android-app/app/src/main/java/io/github/sy220284/codexandroid"
    document = (repository / "docs/对标应用29项界面交付核对表-20261123.md").read_text(encoding="utf-8")
    navigation = (source / "AppNavigation.kt").read_text(encoding="utf-8")
    feature = (source / "UiFeatureCatalog.kt").read_text(encoding="utf-8")
    collector = (repository / "android-app/tools/collect_ui_screenshots.py").read_text(encoding="utf-8")
    policy = json.loads((repository / "android-app/tools/ui_surface_policy.json").read_text(encoding="utf-8"))
    contracts = parse_contracts((repository / "upstream/codex/codex-rs/app-server-protocol/src/protocol/common.rs").read_text(encoding="utf-8"))
    method_policy = policy["methods"]

    source_names = {p.name for p in source.glob("*.kt")}
    tests = "\n".join(p.read_text(encoding="utf-8")
                     for area in ("test", "androidTest")
                     for p in sorted((repository / "android-app/app/src" / area).rglob("*.kt")))
    test_names = set(TEST_METHOD.findall(tests)) | set(TEST_CLASS.findall(tests))
    declared_screens = set(collector.split('REQUIRED_SCREENS = """', 1)[1].split('"""', 1)[0].split())
    declared_screens.update(collector.split('OPTIONAL_SCREENS = [', 1)[1].split(']', 1)[0].replace('"', '').replace("'", '').replace(",", " ").split())
    routes = set(PAGE.findall(navigation))
    features = set(FEATURE.findall(feature))
    rows = []
    issues = []
    by_route = {}
    for match in ROW.finditer(document):
        number, route, priority, goal, code, evidence = match.groups()
        if route in by_route:
            issues.append(f"重复的功能行：{route}")
            continue
        code_refs = SOURCE_NAME.findall(code)
        listed = [name + ".kt" for name in code_refs]
        existing = sorted(name for name in listed if name in source_names)
        missing_files = sorted(set(listed) - source_names)
        cited_tests = sorted(name for name in test_names
                             if re.search(r"\b" + re.escape(name) + r"\b", evidence))
        screenshots = sorted(set(SCREEN.findall(evidence)))
        collected = [screen for screen in screenshots if screen in declared_screens]
        missing_screens = sorted(set(screenshots) - declared_screens)
        if not listed:
            issues.append(f"{route} 未登记 Kotlin 归属文件")
        if missing_files:
            issues.append(f"{route} 文件已失效：{', '.join(missing_files)}")
        if missing_screens:
            issues.append(f"{route} 文档截图未纳入设备验收：{', '.join(missing_screens)}")
        if route not in features:
            issues.append(f"{route} 缺少功能目录")
        if route != "update" and route not in routes:
            issues.append(f"{route} 缺少实际页面路由")
        if not cited_tests:
            issues.append(f"{route} 未关联实际 JUnit/Android 用例，仅登记截图不足以闭环")
        entry = next((line for line in navigation.splitlines()
                      if line.strip().startswith('Page("' + route + '"')), "")
        methods = re.findall(r'"([A-Za-z][A-Za-z0-9_]*/[A-Za-z0-9_/]+)"', entry)
        for method in methods:
            if method not in contracts or method not in method_policy:
                issues.append(f"{route} 引用了未登记 RPC：{method}")
            elif contracts[method].direction != "client_request":
                issues.append(f"{route} 的 UI 路由直接指向 Host 方法：{method}")
            elif method_policy[method].get("surface") in {"后台内部", "平台不适用"}:
                issues.append(f"{route} 的 UI 页面调用了后台专属方法：{method}")
        row = {
            "number": int(number), "route": route, "priority": priority,
            "goal": goal.strip(), "source_files": existing,
            "regression_tests": cited_tests, "screenshot_declarations": collected,
            "entry_rpc": methods, "static_evidence": "已定位" if existing and (cited_tests or collected) else "待补",
            "real_core_receipt": "未逐接口验收", "physical_device_receipt": "未逐功能验收",
        }
        by_route[route] = row
        rows.append(row)

    if len(rows) != expected:
        issues.append(f"文档功能覆盖 {len(rows)}/{expected}")
    if features != set(by_route):
        issues.append(f"功能目录与验收行不一致：{sorted(features ^ set(by_route))}")
    if routes != set(by_route) - {"update"}:
        issues.append(f"页面路由与验收行不一致：{sorted(routes ^ (set(by_route) - {'update'}))}")
    numbers = sorted(row["number"] for row in rows)
    if numbers != list(range(1, expected + 1)):
        issues.append("验收编号不连续")
    if set(contracts) != set(method_policy):
        issues.append(f"Rust 与 UI 方法集合不同：{sorted(set(contracts) ^ set(method_policy))}")
    return {
        "feature_count": len(features),
        "route_count": len(routes),
        "workflow_count": len(rows),
        "rust_method_count": len(contracts),
        "client_methods": sum(c.direction == "client_request" for c in contracts.values()),
        "host_methods": sum(c.direction == "host_request" for c in contracts.values()),
        "workflows": rows,
        "issues": issues,
        "evidence_boundary": "源码、测试名称与预期截图声明；真实内核与真机结果需要对应运行产物",
    }


def markdown(report: dict) -> str:
    lines = [
        "# 111 全功能链路审计索引（自动生成）", "",
        f"- 功能目录：{report['feature_count']}；路由：{report['route_count']}；逐功能台账：{report['workflow_count']}",
        f"- 官方方法：{report['rust_method_count']}；客户端请求：{report['client_methods']}；Host 请求：{report['host_methods']}",
        "- 本表的“已定位”只证明测试与代码位置可追溯，真实写入、回读、取消、断线及设备兼容需独立运行证据。", "",
        "| 编号 | 功能 | 优先级 | 实现文件 | 已声明设备/回归证据 | 实际内核/真机验收 |",
        "| ---: | --- | --- | --- | --- | --- |",
    ]
    for entry in report["workflows"]:
        evidence = ", ".join(entry["regression_tests"] + [
            s + "-android35.png" for s in entry["screenshot_declarations"]
        ])
        lines.append(
            f"| {entry['number']:02} | `{entry['route']}` | {entry['priority']} | "
            f"{', '.join(entry['source_files'])} | {evidence or '未登记'} | 待逐项验证 |"
        )
    lines += ["", "## 本次 CI 审计异常", ""]
    lines += [f"- {issue}" for issue in report["issues"]] if report["issues"] else ["- 无静态目录与证据声明缺口"]
    lines += ["", "## 后续每个真实功能的验收标准", "",
              "入口点击 → 参数与权限 → 官方请求/本机操作 → 服务端回执 → 状态更新/数据保存 → 失败或断线后的恢复；必须记录当前提交和执行环境。", ""]
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", type=Path, default=Path("."))
    parser.add_argument("--json-report", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    result = audit(args.repository)
    args.json_report.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    args.report.write_text(markdown(result), encoding="utf-8")
    for issue in result["issues"]:
        print("功能链路审计异常：" + issue)
    print(f"功能链路：{result['workflow_count']}/31；官方方法：{result['rust_method_count']}；待实机逐条验收。")
    return int(bool(result["issues"]))


if __name__ == "__main__":
    raise SystemExit(main())
