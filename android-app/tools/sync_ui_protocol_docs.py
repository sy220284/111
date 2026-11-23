#!/usr/bin/env python3
"""从唯一的 UI 协议分层策略生成可阅读、可复核的组件映射文档。"""
from __future__ import annotations

import argparse
import difflib
import json
from pathlib import Path
import sys

PREFIX = ["# UI V5 接口到组件全量映射","","> 自动生成文件：修改接口归属时编辑 `android-app/tools/ui_surface_policy.json`，然后运行 `python3 android-app/tools/sync_ui_protocol_docs.py --write`。CI 会检查差异。以锁定的 Rust App Server 协议及 Kotlin `UiProtocolMatrix.kt` 为执行契约。","","## 当前协议职责分类","","分类控制功能展示责任。表中接口出现仅证明已登记；实际调用、参数与设备端执行另行验收。",""]
CATEGORY_DESCRIPTIONS = [["直接操作","页面、快捷入口与用户明确操作"],["上下文操作","选中当前会话、文件或其他对象后使用的动作"],["高级按需","能力探测、业务需要和授权满足后展示"],["后台内部","智能体、内核、事件、流传输及 Host 请求，按需展示审批"],["平台不适用","Android 无适用运行环境"]]
AFTER_COUNTS = ["","## 官方方法逐项归属","","| 官方方法 | 类别 | 功能归属 | 实验 |","| --- | --- | --- | --- |"]
FOOTER = ["","## UI 与后台能力边界","","- 直接及上下文操作按「入口 → 业务对象 → 条件校验 → 真实 RPC/本地等效 → 响应/错误/审批 → 页面刷新」逐项验收。","- 高级能力由内核实际可用性和业务上下文控制。尚未支持的能力显示具体限制和处理路径。","- 后台内部接口不生成独立工具按钮；需要用户决策时在对应任务提供审批表单，按请求 ID 返回合法结果。","- 用户界面呈现中文业务名称，内部 RPC、UUID、配置字段和诊断内容按需展开。","","## 设计与交互验收","","- 移动与大屏保持侧边栏的统一功能归属；对话/任务优先，设置只承载长期配置。","- 统一反馈：输入校验、加载中、审批等待、成功回执、错误原因、恢复操作和当前对象状态。","- 控件最小触控区 48dp，适配 360/393/411dp、系统大字体、TalkBack 及减弱动画。","- Figma 的颜色、组件和页面结构与 `docs/DESIGN_SYSTEM.md` 及 Compose 代码保持一致。","","## 验证级别","","目录一致性、静态调用路径、真实参数契约、模拟器交互与真实 ARM64 内核运行分别留证。静态引用及模拟数据截图不能替代完整运行验收。"]
CATEGORIES = [name for name, _ in CATEGORY_DESCRIPTIONS]


def render_document(policy: dict) -> str:
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


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--policy", type=Path, default=Path("android-app/tools/ui_surface_policy.json"))
    parser.add_argument("--output", type=Path, default=Path("docs/UI_PROTOCOL_COMPONENT_MATRIX_V5.md"))
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--write", action="store_true")
    args = parser.parse_args()
    try:
        generated = render_document(json.loads(args.policy.read_text(encoding="utf-8")))
        if args.write:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(generated, encoding="utf-8")
            print(f"已同步 {args.output}")
            return 0
        existing = args.output.read_text(encoding="utf-8")
    except (OSError, ValueError, KeyError, TypeError) as exc:
        print(f"协议映射文档核验失败：{exc}", file=sys.stderr)
        return 1
    if existing != generated:
        changes = difflib.unified_diff(existing.splitlines(), generated.splitlines(),
                                       fromfile=str(args.output), tofile="策略生成结果", lineterm="")
        print("\n".join(list(changes)[:80]), file=sys.stderr)
        print("协议映射文档与唯一策略不一致，请运行生成命令后提交。", file=sys.stderr)
        return 1
    print("UI 协议组件映射与唯一策略完全一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
