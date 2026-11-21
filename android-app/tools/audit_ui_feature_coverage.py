#!/usr/bin/env python3
"""Audit the Codex Android page catalog against actual Compose routes and RPCs.

This verifies routing and source references, not the runtime success of the
underlying feature. Device instrumentation remains the behavioral gate.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys

PAGE = re.compile(
    r'^\s*Page\(\s*"(?P<id>[a-z]+)"\s*,\s*"(?P<title>[^"]+)"\s*,\s*"(?P<section>[^"]+)"',
    re.M,
)
ROUTE = re.compile(
    r'^\s*(?P<names>"[a-z]+"(?:\s*,\s*"[a-z]+")*)\s*->\s*(?P<handler>[A-Za-z]\w*)\s*\(',
    re.M,
)
NAME = re.compile(r'"([a-z]+)"')
FUNCTION = re.compile(r'\bfun\s+([A-Za-z]\w*)\s*\(')
METHOD = re.compile(r'"([A-Za-z][A-Za-z0-9_]+(?:/[A-Za-z0-9_]+)+)"')
OFFICIAL = re.compile(r'=>\s*"([A-Za-z][A-Za-z0-9_]+(?:/[A-Za-z0-9_]+)+)"\s*\{')


def audit(sources: Path, protocol: Path, policy: Path | None = None) -> dict:
    directory = sources.resolve()
    files = {path.name: path.read_text(encoding="utf-8")
             for path in sorted(directory.rglob("*.kt"))}
    if "AppNavigation.kt" not in files or "Screens.kt" not in files:
        raise ValueError("缺少 AppNavigation.kt 或 Screens.kt，无法核对页面和路由")

    catalog = [{
        "id": m.group("id"), "title": m.group("title"), "section": m.group("section")
    } for m in PAGE.finditer(files["AppNavigation.kt"])]
    ids = [entry["id"] for entry in catalog]
    if len(catalog) < 20 or len(ids) != len(set(ids)):
        raise ValueError("页面清单数量异常或存在重复页面 ID")

    screen = files["Screens.kt"]
    start = screen.find("fun PageContent(")
    end = screen.find("fun ScreenColumn(", start)
    if start == -1 or end == -1:
        raise ValueError("缺少 PageContent 到 ScreenColumn 的路由边界")
    route_source = screen[start:end]
    routes: dict[str, str] = {}
    for match in ROUTE.finditer(route_source):
        for name in NAME.findall(match.group("names")):
            if name in routes:
                raise ValueError(f"页面 {name} 有重复路由")
            routes[name] = match.group("handler")

    defined: dict[str, str] = {}
    for filename, code in files.items():
        for match in FUNCTION.finditer(code):
            defined.setdefault(match.group(1), filename)

    official = set(OFFICIAL.findall(protocol.read_text(encoding="utf-8")))
    if len(official) < 100:
        raise ValueError("官方 App Server 请求表解析异常")
    surfaces = {}
    if policy is not None:
        payload = json.loads(policy.read_text(encoding="utf-8"))
        surfaces = payload.get("methods", {})
        if not isinstance(surfaces, dict):
            raise ValueError("UI 接口分层清单缺少 methods 对象")
        missing_policy = sorted(official - surfaces.keys())
        removed_methods = sorted(surfaces.keys() - official)
        if missing_policy or removed_methods:
            raise ValueError(
                f"UI 接口分层需要同步最新官方协议：新增/未归类 {missing_policy}；"
                f"已经删除但仍记录 {removed_methods}"
            )
        allowed_surfaces = {"直接操作", "上下文操作", "高级按需", "后台内部", "平台不适用"}
        invalid = sorted(
            name for name, entry in surfaces.items()
            if not isinstance(entry, dict) or entry.get("surface") not in allowed_surfaces
        )
        if invalid:
            raise ValueError(f"UI 接口分层缺少有效分类：{invalid}")
    called: dict[str, list[str]] = {}
    for filename, code in files.items():
        for name in METHOD.findall(code):
            if name in official:
                called.setdefault(name, [])
                if filename not in called[name]:
                    called[name].append(filename)

    missing = sorted(set(ids) - set(routes))
    unknown = sorted(set(routes) - set(ids))
    unimplemented = sorted({
        f"{name} -> {handler}" for name, handler in routes.items()
        if handler not in defined
    })
    entries = [{
        **page,
        "handler": routes.get(page["id"], "缺失"),
        "source": defined.get(routes.get(page["id"], ""), "缺失")
    } for page in catalog]
    return {
        "pageCount": len(catalog),
        "routedCount": len(catalog) - len(missing),
        "officialRequests": len(official),
        "referencedRequests": len(called),
        "pages": entries,
        "referencedMethodSources": dict(sorted(called.items())),
        "surfacePolicy": dict(sorted(surfaces.items())),
        "missingRoutes": missing,
        "unknownRoutes": unknown,
        "undefinedHandlers": unimplemented,
    }


def report_markdown(result: dict) -> str:
    lines = [
        "# Codex Android UI 覆盖审计（CI 自动生成）",
        "",
        f"- 已定义页面：**{result['pageCount']}**",
        f"- 已映射路由：**{result['routedCount']}**",
        f"- 官方请求总数：**{result['officialRequests']}**",
        f"- Android 源码显式引用的官方请求：**{result['referencedRequests']}**",
        "",
        "说明：以上是源码可达性及协议名称核对；页面导航、真实操作成功和设备兼容性须使用 Android 设备测试验证。",
        "",
        "| 分组 | 页面 | ID | 实际组件 | 源文件 |",
        "| --- | --- | --- | --- | --- |",
    ]
    for page in result["pages"]:
        lines.append(
            f"| {page['section']} | {page['title']} | `{page['id']}` | "
            f"`{page['handler']}` | `{page['source']}` |"
        )
    lines += [
        "",
        "## 官方请求实际引用",
        "",
        "| 方法 | Kotlin 来源 |",
        "| --- | --- |",
    ]
    for method, sources in result["referencedMethodSources"].items():
        lines.append(f"| `{method}` | {', '.join(sources)} |")
    if result["surfacePolicy"]:
        lines += [
            "", "## 用户可见与后台能力分层（锁定的官方全量接口）", "",
            "| 官方方法 | UI 层级 | 业务归属 | Android 源码引用 |",
            "| --- | --- | --- | --- |",
        ]
        for method, entry in result["surfacePolicy"].items():
            lines.append(
                f"| `{method}` | {entry['surface']} | {entry['owner']} | "
                f"{'已引用' if method in result['referencedMethodSources'] else '尚未直接引用'} |"
            )
    lines.extend(["", "## 缺失/冲突", ""])
    for title, field in [
        ("没有页面路由", "missingRoutes"),
        ("多余路由", "unknownRoutes"),
        ("路由组件不存在", "undefinedHandlers"),
    ]:
        value = result[field]
        lines.append(f"- {title}：{', '.join(value) if value else '无'}")
    lines.append("")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sources", type=Path, required=True)
    parser.add_argument("--protocol", type=Path, required=True)
    parser.add_argument("--policy", type=Path)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--json-report", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = audit(args.sources, args.protocol, args.policy)
    except (OSError, ValueError) as failure:
        print(f"UI 审计失败：{failure}", file=sys.stderr)
        return 1

    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.json_report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(report_markdown(result), encoding="utf-8")
    args.json_report.write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(
        f"UI 页面对照 {result['routedCount']}/{result['pageCount']}；"
        f"官方请求引用 {result['referencedRequests']}/{result['officialRequests']}。"
    )
    for field in ("missingRoutes", "unknownRoutes", "undefinedHandlers"):
        if result[field]:
            print(f"{field}：{result[field]}", file=sys.stderr)
    return int(any(result[field] for field in (
        "missingRoutes", "unknownRoutes", "undefinedHandlers"
    )))


if __name__ == "__main__":
    sys.exit(main())
