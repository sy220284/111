#!/usr/bin/env python3
"""从唯一的 UI 协议分层策略生成可阅读、可复核的组件映射文档。"""
from __future__ import annotations

import argparse
import difflib
import json
import re
from pathlib import Path
import sys

from protocol_contract import request_method_names

PREFIX = ["# UI V5 接口到组件全量映射","","> 自动生成文件：修改接口归属时编辑 `android-app/tools/ui_surface_policy.json`，然后运行 `python3 android-app/tools/sync_ui_protocol_docs.py --write`。CI 会检查差异。以锁定的 Rust App Server 协议及 Kotlin `UiProtocolMatrix.kt` 为执行契约。","","## 当前协议职责分类","","分类控制功能展示责任。表中接口出现仅证明已登记；实际调用、参数与设备端执行另行验收。",""]
CATEGORY_DESCRIPTIONS = [["直接操作","页面、快捷入口与用户明确操作"],["上下文操作","选中当前会话、文件或其他对象后使用的动作"],["高级按需","能力探测、业务需要和授权满足后展示"],["后台内部","智能体、内核、事件、流传输及 Host 请求，按需展示审批"],["平台不适用","Android 无适用运行环境"]]
AFTER_COUNTS = ["","## 官方方法逐项归属","","| 官方方法 | 类别 | 功能归属 | 实验 |","| --- | --- | --- | --- |"]
FOOTER = ["","## UI 与后台能力边界","","- 直接及上下文操作按「入口 → 业务对象 → 条件校验 → 真实 RPC/本地等效 → 响应/错误/审批 → 页面刷新」逐项验收。","- 高级能力由内核实际可用性和业务上下文控制。尚未支持的能力显示具体限制和处理路径。","- 后台内部接口不生成独立工具按钮；需要用户决策时在对应任务提供审批表单，按请求 ID 返回合法结果。","- 用户界面呈现中文业务名称，内部 RPC、UUID、配置字段和诊断内容按需展开。","","## 设计与交互验收","","- 手机竖屏统一顶部导航与抽屉侧栏；对话/任务优先，设置只承载长期配置，不引入横屏、平板或业务底栏。","- 统一反馈：输入校验、加载中、审批等待、成功回执、错误原因、恢复操作和当前对象状态。","- 控件最小触控区 48dp，按目标提交的实际 CI 设备矩阵执行屏宽、系统大字体、TalkBack 及减弱动画验收；详见 CI 统一交付流程，历史矩阵不替代当前门禁。","- Figma 的颜色、组件和页面结构与 `docs/设计系统.md` 及 Compose 代码保持一致。","","## 验证级别","","目录一致性、静态调用路径、真实参数契约、模拟器交互与真实 ARM64 内核运行分别留证。静态引用及模拟数据截图不能替代完整运行验收。"]
CATEGORIES = [name for name, _ in CATEGORY_DESCRIPTIONS]


def category_counts(policy: dict) -> dict[str, int]:
    methods = policy["methods"]
    if not isinstance(methods, dict) or not methods:
        raise ValueError("协议分层清单缺少 methods 对象")
    counts = {name: 0 for name in CATEGORIES}
    for method, record in methods.items():
        category = record["surface"]
        if category not in counts or not record.get("owner"):
            raise ValueError(f"未知类别或缺少归属：{method}")
        if record.get("flag") not in ("实验", "常规/内部"):
            raise ValueError(f"无效实验标记：{method}")
        counts[category] += 1
    return counts


def render_document(policy: dict) -> str:
    methods = policy["methods"]
    counts = category_counts(policy)
    lines = PREFIX + [
        f"**总数 {len(methods)}：直接 {counts['直接操作']}、上下文 {counts['上下文操作']}、高级 {counts['高级按需']}、后台 {counts['后台内部']}、平台不适用 {counts['平台不适用']}。**",
        "",
        "| 类别 | 数量 | 使用方式 |",
        "| --- | ---: | --- |",
    ]
    lines += [
        f"| {name} | {counts[name]} | {description} |"
        for name, description in CATEGORY_DESCRIPTIONS
    ]
    lines += AFTER_COUNTS
    for method, record in methods.items():
        experimental = "是" if record["flag"] == "实验" else "否"
        lines.append(f"| {chr(96)}{method}{chr(96)} | {record['surface']} | {record['owner']} | {experimental} |")
    equivalents = policy.get("implementationEquivalents", {})
    lines += ["", f"本地文件操作等效实现登记：**{len(equivalents)}** 项。"]
    lines += FOOTER
    return "\n".join(lines) + "\n"



def render_ledger(ledger: str, policy: dict, clients: set[str], hosts: set[str]) -> str:
    """Update only current summary counts; historical evidence and per-method notes stay intact."""
    counts = category_counts(policy)
    if clients & hosts or clients | hosts != set(policy["methods"]):
        raise ValueError("权威台账统计：官方请求集合与 UI 策略不一致")
    rows = {
        "客户端→Codex 服务端请求": len(clients),
        "Codex 服务端→Android Host 请求": len(hosts),
        "协议方法合计": len(policy["methods"]),
        **counts,
    }
    # A missing or duplicate row is a real documentation error, not a reason
    # to silently generate a second summary or touch a historical section.
    for label, count in rows.items():
        pattern = r"(?m)^(\| " + re.escape(label) + r" \| )\d+( \|.*)$"
        ledger, changed = re.subn(pattern, lambda m: m[1] + str(count) + m[2], ledger)
        if changed != 1:
            raise ValueError(f"权威台账统计行缺失或重复：{label}")
    labels = {"用户可直接操作": "直接操作", "对象操作": "上下文操作",
              "高级按需": "高级按需", "智能体/内核后台": "后台内部",
              "平台不适用": "平台不适用"}
    for label, category in labels.items():
        pattern = r"(?m)^(- \*\*" + re.escape(label) + r"（)\d+(）\*\*：.*)$"
        ledger, changed = re.subn(pattern, lambda m: m[1] + str(counts[category]) + m[2], ledger)
        if changed != 1:
            raise ValueError(f"权威台账职责行缺失或重复：{label}")
    ledger, changed = re.subn(r"不在上述 \d+ 项带斜杠方法中",
                              f"不在上述 {len(policy['methods'])} 项带斜杠方法中", ledger)
    if changed != 1:
        raise ValueError("权威台账握手边界说明缺失或重复")
    return ledger


SURFACE_TO_KOTLIN = {"直接操作":"Direct","上下文操作":"Contextual","高级按需":"Advanced","后台内部":"Internal","平台不适用":"Unsupported"}
KOTLIN_PREFIX = ["package io.github.sy220284.codexandroid","","/**"," * 由 android-app/tools/ui_surface_policy.json 生成。官方协议字段与方法方向由 CI 核验。"," * 元数据用于 UI 入口归属，实际功能接入必须有真实请求/等效操作与设备测试。"," */","enum class ApiExposure { Direct, Contextual, Advanced, Internal, Unsupported }","data class ProtocolSurface(","    val method: String, val exposure: ApiExposure,","    val owner: String, val experimental: Boolean",")","object UiProtocolMatrix {","    val all = listOf("]
KOTLIN_SUFFIX = ["    )","    val byMethod = all.associateBy { it.method }","    val userFacing = all.filter {","        it.exposure == ApiExposure.Direct || it.exposure == ApiExposure.Contextual","    }","    val advanced = all.filter { it.exposure == ApiExposure.Advanced }","    val systemOnly = all.filter {","        it.exposure == ApiExposure.Internal || it.exposure == ApiExposure.Unsupported","    }","}"]


def render_kotlin(policy: dict) -> str:
    methods = policy["methods"]
    rows = []
    for index, (method, record) in enumerate(methods.items()):
        surface = SURFACE_TO_KOTLIN.get(record["surface"])
        if not surface:
            raise ValueError(f"未知展示类别：{method}")
        experimental = "true" if record["flag"] == "实验" else "false"
        comma = "," if index < len(methods) - 1 else ""
        rows.append(
            "        ProtocolSurface(" +
            json.dumps(method, ensure_ascii=False) +
            ", ApiExposure." + surface + ", " +
            json.dumps(record["owner"], ensure_ascii=False) +
            ", " + experimental + ")" + comma
        )
    return "\n".join(KOTLIN_PREFIX + rows + KOTLIN_SUFFIX) + "\n"



def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--policy", type=Path, default=Path("android-app/tools/ui_surface_policy.json"))
    parser.add_argument("--output", type=Path, default=Path("docs/第五版接口与界面组件映射矩阵.md"))
    parser.add_argument("--matrix", type=Path, default=Path(
        "android-app/app/src/main/java/io/github/sy220284/codexandroid/UiProtocolMatrix.kt"))
    parser.add_argument("--ledger", type=Path, default=Path("docs/接口协议动作清单与落地验收台账-20261122.md"))
    parser.add_argument("--official", type=Path, default=Path(
        "upstream/codex/codex-rs/app-server-protocol/src/protocol/common.rs"))
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--write", action="store_true")
    args = parser.parse_args()
    try:
        policy = json.loads(args.policy.read_text(encoding="utf-8"))
        clients, hosts = request_method_names(args.official.read_text(encoding="utf-8"))
        artifacts = {
            args.output: render_document(policy),
            args.matrix: render_kotlin(policy),
            args.ledger: render_ledger(args.ledger.read_text(encoding="utf-8"), policy, clients, hosts),
        }
        if args.write:
            for path, content in artifacts.items():
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content, encoding="utf-8")
                print("已同步 " + str(path))
            return 0
        stale = []
        for path, content in artifacts.items():
            old = path.read_text(encoding="utf-8")
            if old == content:
                continue
            stale.append(path)
            diff = difflib.unified_diff(old.splitlines(), content.splitlines(),
                                        fromfile=str(path), tofile="统一策略生成", lineterm="")
            print("\n".join(list(diff)[:80]), file=sys.stderr)
    except (OSError, ValueError, KeyError, TypeError) as exc:
        print("协议资产核验失败：" + str(exc), file=sys.stderr)
        return 1
    if stale:
        print("协议资产有漂移，请重新运行 --write：" +
              "，".join(map(str, stale)), file=sys.stderr)
        return 1
    print("UI 协议 Kotlin 目录、组件文档和权威台账统计均与官方协议及唯一策略一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
