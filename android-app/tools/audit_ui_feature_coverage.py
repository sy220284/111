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

from protocol_contract import parse_contracts

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


# 静态调用证据与方法名称引用分开计数。解析参数括号，避免漏掉拼接 key。
RPC_CALL_HEAD = re.compile(
    r'\b(?P<callee>(?:(?:vm\.)?bridge\.)?request(?:ForSession)?|(?:vm\.)?capabilityRequest|(?:vm\.)?(?:rpc|refresh|ensureCatalog))\s*\('
)
DYNAMIC_METHOD = re.compile(
    r'\bval\s+(?P<variable>[A-Za-z_]\w*)\s*=\s*if\s*\([^)]*\)\s*'
    r'"(?P<first>[A-Za-z][A-Za-z0-9_]+(?:/[A-Za-z0-9_]+)+)"\s*else\s*'
    r'"(?P<second>[A-Za-z][A-Za-z0-9_]+(?:/[A-Za-z0-9_]+)+)"'
)



FEATURE = re.compile(
    r'UiFeature\("(?P<id>[a-z]+)",\s*FeatureSurface\.(?P<surface>\w+),'
    r'\s*FeatureOwner\.(?P<owner>\w+),\s*(?:true|false)\)'
)
# The shell dispatches sheets to exactly one contextual overlay; they should not
# silently turn back into top-level pages when the catalog grows.
SHEET_DISPATCH = {
    "threads": ("openDrawer()", "SidebarSessions("),
    "search": ("openDrawer()", "SidebarSessions("),
    "approvals": ('openSheet("approvals")', "ApprovalQuickSheet("),
    "goals": ('openSheet("goals")', "ThreadGoalSheet("),
    "queue": ('openSheet("queue")', "ThreadQueueQuickSheet("),
    "review": ('openSheet("review")', "ReviewScopeSheet("),
    "models": ('openSheet("models")', "ModelChoiceSurface("),
    "modes": ('openSheet("modes")', "ModeChoiceSurface("),
    "about": ('openSheet("about")', "AboutInfoSheet("),
}
INLINE_DISPATCH = {
    "security": ("ConversationScreen.kt", "ComposerPermissionControl("),
    "appearance": ("SettingsHub.kt", "AppearanceInlineControls("),
    "update": ("SettingsHub.kt", "AppUpdateCard("),
}
CONTROL_MARKER = re.compile(
    r'\b(?:Button|OutlinedButton|TextButton|IconButton|FilterChip|'
    r'DropdownMenuItem|Switch|Checkbox|RadioButton)\s*\('
)
EMPTY_HANDLER = re.compile(
    r'\b(?:onClick|onConfirm|onDismissRequest)\s*=\s*\{\s*\}'
    r'|\.clickable\s*\{\s*\}'
)


def audit_surface_hierarchy(files: dict[str, str], page_ids: set[str]) -> dict:
    code = files.get("UiFeatureCatalog.kt", "")
    navigation = files.get("AppNavigation.kt", "")
    features = [match.groupdict() for match in FEATURE.finditer(code)]
    ids = [item["id"] for item in features]
    invalid = []
    if len(ids) != len(set(ids)):
        invalid.append("功能目录包含重复路由")
    if set(ids) != page_ids | {"update"}:
        invalid.append("功能目录与页面清单的注册项不一致")
    settings_block = code.split("val settingsGroups = listOf(", 1)[-1].split(
        "val legacyRoutes", 1
    )[0]
    settings_ids = [
        name for group in re.findall(r'FeatureGroup\("[^"]+",\s*listOf\(([^)]*)\)', settings_block)
        for name in NAME.findall(group)
    ]
    if len(settings_ids) != len(set(settings_ids)):
        invalid.append("设置首页存在重复的功能入口")
    if len({"usage", "limits"} & set(settings_ids)) != 1:
        invalid.append("用量和额度必须由同一个账号入口管理")
    task_actions = {"threads", "approvals", "search", "goals", "queue",
                    "diff", "terminal", "review", "modes", "security"}
    if task_actions & set(settings_ids):
        invalid.append("设置首页混入会话或工作区操作")
    move_start = navigation.find("val move:")
    move_end = navigation.find("if (page.id != destination)", move_start)
    route_handlers = navigation[move_start:move_end] if move_start >= 0 and move_end > move_start else ""
    missing_surfaces = []
    for feature in features:
        name, kind = feature["id"], feature["surface"]
        if kind == "Sheet":
            expected = SHEET_DISPATCH.get(name)
            if expected is None:
                missing_surfaces.append(f"{name}:缺少弹层归属")
            elif (f'"{name}"' not in route_handlers
                  or expected[0] not in route_handlers or expected[1] not in navigation
                  or (name not in {"threads", "search"} and
                      not re.search(r'if \(activeSheet == "' + re.escape(name) +
                                    r'"\)[^\n]*(?:\n[^\n]*){0,2}?' + re.escape(expected[1]), navigation))):
                missing_surfaces.append(f"{name}:弹层入口或组件未接通")
        elif kind == "Inline":
            expected = INLINE_DISPATCH.get(name)
            if expected is None or expected[1] not in files.get(expected[0], ""):
                missing_surfaces.append(f"{name}:内嵌组件未接通")
        elif kind not in {"Page", "Reader"}:
            missing_surfaces.append(f"{name}:未知展示类型 {kind}")
    empty_actions = [
        f"{name}:{code[:match.start()].count(chr(10)) + 1}"
        for name, code in files.items()
        for match in EMPTY_HANDLER.finditer(code)
    ]
    controls = {
        name: len(CONTROL_MARKER.findall(source))
        for name, source in files.items()
        if CONTROL_MARKER.search(source)
    }
    return {
        "featureCount": len(features),
        "surfaceCounts": {
            kind: sum(f["surface"] == kind for f in features)
            for kind in ("Page", "Sheet", "Reader", "Inline", "Action", "Dialog")
        },
        "settingsEntryCount": len(settings_ids),
        "interactiveControls": sum(controls.values()),
        "controlSources": dict(sorted(controls.items())),
        "invalidHierarchy": invalid,
        "missingSurfaceMappings": missing_surfaces,
        "emptyActionHandlers": empty_actions,
    }


def _call_arguments(source: str, begin: int) -> list[str]:
    """切分 Kotlin 实参，忽略嵌套表达式和引号中的逗号、括号。"""
    args: list[str] = []
    stack: list[str] = []
    quoted = False
    escaped = False
    start = begin
    for i in range(begin, len(source)):
        ch = source[i]
        if quoted:
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == '"':
                quoted = False
            continue
        if ch == '"':
            quoted = True
        elif ch in "([{":
            stack.append(ch)
        elif ch in ")]}":
            if not stack and ch == ")":
                args.append(source[start:i].strip())
                return args
            if not stack:
                return []
            opening = stack.pop()
            if (opening, ch) not in (("(", ")"), ("[", "]"), ("{", "}")):
                return []
        elif ch == "," and not stack:
            args.append(source[start:i].strip())
            start = i + 1
    return []


def declared_method_resolvers(files: dict[str, str], official: set[str]) -> dict[str, set[str]]:
    """Trace the single checked organization policy, rather than requiring duplicate literal RPC calls."""
    source = files.get("ThreadBulkActionPolicy.kt", "")
    match = re.search(r'fun\s+method\(kind:\s*String\)\s*:\s*String\s*=\s*when\s*\(kind\)\s*\{([^}]+)\}', source)
    if match is None:
        return {}
    methods = set(re.findall(r'^\s*"[^"]+"\s*->\s*"([^"]+)"\s*$', match.group(1), re.M))
    if methods != {"thread/archive", "thread/unarchive", "thread/delete"}:
        return {}
    return {"ThreadBulkActionPolicy.method": methods & official}


def find_rpc_calls(code: str, official: set[str], resolvers: dict[str, set[str]] | None = None) -> set[str]:
    found: set[str] = set()
    candidates = {
        m.group("variable"): {m.group("first"), m.group("second")}
        for m in DYNAMIC_METHOD.finditer(code)
    }
    for match in re.finditer(r'\bval\s+(\w+)\s*=\s*(?:try\s*\{\s*)?([A-Za-z_]\w*\.\w+)\s*\(', code):
        if match.group(2) in (resolvers or {}):
            candidates[match.group(1)] = resolvers[match.group(2)]
    for match in RPC_CALL_HEAD.finditer(code):
        args = _call_arguments(code, match.end())
        index = 0 if match.group("callee").rsplit(".", 1)[-1] in {"request", "requestForSession", "capabilityRequest"} else 1
        if len(args) <= index:
            continue
        expression = args[index]
        found.update(name for name in METHOD.findall(expression) if name in official)
        if expression in candidates:
            found.update(candidates[expression] & official)
    return found


def audit(sources: Path, protocol: Path, policy: Path | None = None) -> dict:
    directory = sources.resolve()
    files = {path.name: path.read_text(encoding="utf-8")
             for path in sorted(directory.rglob("*.kt"))}
    return audit_sources(files,parse_contracts(protocol.read_text(encoding="utf-8")),
                         json.loads(policy.read_text(encoding="utf-8")) if policy is not None else None)


def audit_sources(files, contracts, payload):
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

    # Threads and search are logical capabilities of the same drawer, not pages.
    # Verify actual navigation and the single owner instead of requiring dummy page handlers.
    shell = files["AppNavigation.kt"]
    if '"threads", "search" -> {' in shell and "SidebarSessions(" in shell:
        for name in ("threads", "search"):
            if name in routes:
                raise ValueError(f"会话能力 {name} 重复注册为独立页面")
            routes[name] = "SidebarSessions"
    if "ComposerPermissionControl(" in files.get("ConversationScreen.kt", "") and "security" not in routes:
        routes["security"] = "ComposerPermissionControl"

    defined: dict[str, str] = {}
    for filename, code in files.items():
        for match in FUNCTION.finditer(code):
            defined.setdefault(match.group(1), filename)

    official = set(contracts)
    client_requests = {name for name, entry in contracts.items()
                       if entry.direction == "client_request"}
    host_requests = official - client_requests
    surfaces = {}
    if payload is not None:
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
    # 接口声明目录只是元数据，不得误算成实际功能接入。
    declaration_files = {"UiProtocolMatrix.kt", "UiFeatureCatalog.kt", "AppNavigation.kt"}
    called: dict[str, list[str]] = {}
    for filename, code in files.items():
        if filename in declaration_files:
            continue
        for name in METHOD.findall(code):
            if name in client_requests:
                called.setdefault(name, [])
                if filename not in called[name]:
                    called[name].append(filename)

    call_sites: dict[str, list[str]] = {}
    resolvers = declared_method_resolvers(files, client_requests)
    for filename, source in files.items():
        if filename in declaration_files:
            continue
        for method in find_rpc_calls(source, client_requests, resolvers):
            call_sites.setdefault(method, []).append(filename)

    equivalents: dict[str, dict] = {}
    user_facing: set[str] = set()
    if payload is not None:
        equivalents = payload.get("implementationEquivalents", {})
        if not isinstance(equivalents, dict):
            raise ValueError("implementationEquivalents 必须为 JSON 对象")
        user_facing = {
            method for method, entry in surfaces.items()
            if entry["surface"] in {"直接操作", "上下文操作"}
        }
        for method, entry in equivalents.items():
            if method not in user_facing:
                raise ValueError(f"等效实现引用了未知或非用户可见接口：{method}")
            if not isinstance(entry, dict) or entry.get("kind") not in {"local", "conditional"}:
                raise ValueError(f"等效实现缺少有效类别：{method}")
            source_name = entry.get("source")
            evidence = entry.get("evidence")
            reason = entry.get("reason")
            if (not isinstance(source_name, str) or source_name not in files
                    or not isinstance(evidence, str) or not evidence
                    or evidence not in files[source_name]
                    or not isinstance(reason, str) or not reason):
                raise ValueError(f"等效实现缺少代码证据或说明：{method}")
        unmapped = sorted(user_facing - set(called) - set(equivalents))
        if unmapped:
            raise ValueError("用户可见请求缺少实际代码引用或等效路径：" + ", ".join(unmapped))
        untraced = sorted(user_facing - set(call_sites) - set(equivalents))
        if untraced:
            raise ValueError("用户可见请求仅有字符串引用、缺少可追踪调用：" + ", ".join(untraced))

    missing = sorted(set(ids) - set(routes))
    unknown = sorted(set(routes) - set(ids))
    unimplemented = sorted({
        f"{name} -> {handler}" for name, handler in routes.items()
        if handler not in defined
    })
    hierarchy = audit_surface_hierarchy(files, set(ids))
    entries = [{
        **page,
        "handler": routes.get(page["id"], "缺失"),
        "source": defined.get(routes.get(page["id"], ""), "缺失")
    } for page in catalog]
    return {
        **hierarchy,
        "pageCount": len(catalog),
        "routedCount": len(catalog) - len(missing),
        "officialRequests": len(official),
        "clientRequests": len(client_requests),
        "hostRequests": len(host_requests),
        "hostRequestMethods": sorted(host_requests),
        "referencedRequests": len(called),
        "rpcCallsiteRequests": len(call_sites),
        "tracedUserFacingRequests": len(user_facing & (set(call_sites) | set(equivalents))),
        "rpcCallsiteSources": dict(sorted(call_sites.items())),
        "methodOnlyReferences": sorted(set(called) - set(call_sites)),
        "userFacingRequests": len(user_facing),
        "equivalentRequests": len(equivalents),
        "implementationEquivalents": dict(sorted(equivalents.items())),
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
        f"- 注册功能：**{result['featureCount']}**；展示方式：{result['surfaceCounts']}",
        f"- 设置入口：**{result['settingsEntryCount']}**；静态控件总数：**{result['interactiveControls']}**",
        f"- 已映射路由：**{result['routedCount']}**",
        f"- 官方协议方法总数：**{result['officialRequests']}**", 
        f"- Android 可主动请求：**{result['clientRequests']}**", 
        f"- 内核向 Android 发起的 Host 请求：**{result['hostRequests']}**", 
        f"- Android 非声明目录代码中显式引用的官方请求：**{result['referencedRequests']}**",
        f"- Android 静态 RPC 调用表达式所涉及方法：**{result['rpcCallsiteRequests']}**",
        f"- 用户可见请求总数：**{result['userFacingRequests']}**",
        f"- 经可追踪调用或本地等效验证的用户请求：**{result['tracedUserFacingRequests']}**",
        f"- 已核对的本地等效/条件实现：**{result['equivalentRequests']}**",
        "",
        "说明：方法名称引用与静态调用表达式单独统计。静态表达式仍可能被条件分支阻断；页面点击和实际运行成功须经 Android 设备验证。", 
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
        "## Android 客户端请求的源码引用（静态证据）",
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
    lines.extend(["", "## 层级、操作与弹层审查", ""])
    for label, field in [
        ("入口与归属冲突", "invalidHierarchy"),
        ("展示组件未接通", "missingSurfaceMappings"),
        ("空操作处理器", "emptyActionHandlers"),
    ]:
        value = result[field]
        lines.append(f"- {label}：{', '.join(value) if value else '无'}")
    lines.append("")
    lines.append("静态检查可发现重复入口、缺失弹层映射与空点击处理器；"
                 "所有按钮的真实操作结果仍以 Android 设备回归和服务端响应为准。")
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


CHECK_FIELDS = ("missingRoutes", "unknownRoutes", "undefinedHandlers",
                "invalidHierarchy", "missingSurfaceMappings", "emptyActionHandlers")

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
    checked = CHECK_FIELDS
    for field in checked:
        if result[field]:
            print(f"{field}：{result[field]}", file=sys.stderr)
    return int(any(result[field] for field in checked))


if __name__ == "__main__":
    sys.exit(main())
