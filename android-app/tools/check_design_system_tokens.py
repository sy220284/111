#!/usr/bin/env python3
"""Validate the documented Android/Figma palette against actual Compose Ink tokens."""
from __future__ import annotations
import argparse
import json
import re
import sys
from pathlib import Path

TOKEN = re.compile(r'\bval\s+(\w+)\s*=\s*Color\(0xFF([0-9a-fA-F]{6})\)')
ROW = re.compile(r'^\|\s*`(\w+)`\s*\|\s*`#([a-fA-F0-9]{6})`\s*\|\s*(.*?)\s*\|$', re.M)
BEGIN = '<!-- INK_TOKENS_BEGIN -->'
END = '<!-- INK_TOKENS_END -->'


def verify(source: str, spec: str) -> list[str]:
    errors: list[str] = []
    def object_block(name: str) -> str | None:
        match = re.search(r'\bobject\s+' + re.escape(name) + r'\s*\{(.*?)\n\}', source, re.S)
        return match.group(1) if match else None

    light_source = object_block('InkLight') or object_block('Ink') or ''
    found = list(TOKEN.finditer(light_source))
    actual = {m.group(1): m.group(2).upper() for m in found}
    if len(actual) < 12 or len(found) != len(actual):
        errors.append('Compose Ink 颜色项数量异常或有重复声明')
    if spec.count(BEGIN) != 1 or spec.count(END) != 1:
        return errors + ['规范必须只有一段 INK_TOKENS 标记']
    between = spec.split(BEGIN, 1)[1].split(END, 1)[0]
    declared = list(ROW.finditer(between))
    wanted = {m.group(1): m.group(2).upper() for m in declared}
    if len(declared) != len(wanted):
        errors.append('设计颜色表中存在重复字段')
    for name in sorted(actual.keys() - wanted.keys()):
        errors.append(f'设计规范缺失 Ink.{name}')
    for name in sorted(wanted.keys() - actual.keys()):
        errors.append(f'设计规范中存在未定义 Ink.{name}')
    for name in sorted(actual.keys() & wanted.keys()):
        if actual[name] != wanted[name]:
            errors.append(f'Ink.{name} 颜色不一致：Compose #{actual[name]} / 规范 #{wanted[name]}')
    dark_source = object_block('InkDark')
    if dark_source is not None:
        dark_found = list(TOKEN.finditer(dark_source))
        dark_actual = {match.group(1): match.group(2).upper() for match in dark_found}
        start = '<!-- INK_DARK_TOKENS_BEGIN -->'
        end = '<!-- INK_DARK_TOKENS_END -->'
        if spec.count(start) != 1 or spec.count(end) != 1:
            errors.append('深色设计变量表缺失或重复')
            dark_declared = []
        else:
            dark_declared = list(ROW.finditer(spec.split(start, 1)[1].split(end, 1)[0]))
        dark_wanted = {match.group(1): match.group(2).upper() for match in dark_declared}
        if set(dark_actual) != set(actual) or len(dark_found) != len(dark_actual):
            errors.append('深色颜色项不完整或存在重复声明')
        for name in sorted(set(dark_actual) | set(dark_wanted)):
            if dark_actual.get(name) != dark_wanted.get(name):
                errors.append('深色 Ink.' + name + ' 颜色不一致')
        for name in actual:
            getter = (r'\bval\s+' + re.escape(name) +
                r'\s*:\s*Color\s+get\(\)\s*=\s*if\s*\(UiPreferences\.darkMode\.value\)')
            if not re.search(getter, source):
                errors.append('Ink.' + name + ' 未绑定明暗主题')
        if 'darkColorScheme(' not in source:
            errors.append('缺少 Material 深色主题')
        def luminosity(hex_color: str) -> float:
            values = [int(hex_color[i:i + 2], 16) / 255.0 for i in (0, 2, 4)]
            linear = [c / 12.92 if c <= .04045 else ((c + .055) / 1.055) ** 2.4 for c in values]
            return linear[0] * .2126 + linear[1] * .7152 + linear[2] * .0722
        for mode, palette in (('亮色', actual), ('深色', dark_actual)):
            if not all(k in palette for k in ('canvas', 'parchment', 'muted', 'coral')):
                continue
            bg = luminosity(palette['canvas'])
            for key in ('parchment', 'muted', 'coral'):
                fg = luminosity(palette[key])
                value = (max(fg, bg) + .05) / (min(fg, bg) + .05)
                if value < 4.5:
                    errors.append(mode + ' Ink.' + key + ' 对比度低于 4.5:1')
            if 'controlBorder' in palette:
                fg = luminosity(palette['controlBorder'])
                for surface in ('canvas', 'panel', 'recessed', 'elevated'):
                    if surface not in palette:
                        continue
                    surface_bg = luminosity(palette[surface])
                    if (max(fg, surface_bg) + .05) / (min(fg, surface_bg) + .05) < 3:
                        errors.append(mode + ' Ink.controlBorder 与 ' + surface + ' 对比度低于 3:1')
    if not re.search(r'\bprimary\s*=\s*Ink\.coral\b', source):
        errors.append('MaterialTheme.primary 应映射 Ink.coral')
    if not re.search(r'\bonPrimary\s*=\s*Ink\.canvas\b', source):
        errors.append('MaterialTheme.onPrimary 应映射 Ink.canvas')
    for match in declared:
        if not match.group(3).strip():
            errors.append(f'Ink.{match.group(1)} 缺少使用说明')
    return errors


def verify_asset_tokens(source: str, asset_json: str) -> list[str]:
    """Figma 导入变量与 Compose 的亮/暗语义颜色必须逐项一致。"""
    try:
        tokens = json.loads(asset_json)
    except (json.JSONDecodeError, TypeError) as exc:
        return ["Figma tokens.json 无法解析：" + str(exc)]
    modes = tokens.get("modes")
    if not isinstance(modes, dict):
        return ["Figma tokens.json 缺少 modes 对象"]
    errors: list[str] = []
    palette_names: set[str] | None = None
    for mode, object_name in (("light", "InkLight"), ("dark", "InkDark")):
        match = re.search(r'\bobject\s+' + object_name + r'\s*\{(.*?)\n\}', source, re.S)
        if match is None:
            errors.append("Compose 主题缺少 " + object_name)
            continue
        actual_matches = list(TOKEN.finditer(match.group(1)))
        actual = {item.group(1): "#" + item.group(2).upper() for item in actual_matches}
        wanted = modes.get(mode)
        if not isinstance(wanted, dict):
            errors.append("Figma tokens.json 缺少 " + mode + " 模式")
            continue
        if len(actual_matches) != len(actual):
            errors.append(object_name + " 存在重复颜色定义")
        if palette_names is not None and set(actual) != palette_names:
            errors.append("Compose 亮暗模式的语义颜色键集合不一致")
        palette_names = set(actual)
        for name in sorted(set(actual) | set(wanted)):
            if actual.get(name) != str(wanted.get(name, "")).upper():
                errors.append("Figma " + mode + " Ink." + name + " 与 Compose 颜色不一致")
    sizes = tokens.get("sizes", {})
    if not isinstance(sizes, dict) or sizes.get("touchTargetDp", 0) < 48:
        errors.append("Figma 触控规格应至少为 48dp")
    return errors


MOTION_KEYS = {
    "press": "press", "release": "release", "selection": "selection",
    "menu": "menu", "reveal": "reveal", "page": "navigation",
    "imageZoom": "imageZoom", "success": "success", "sheet": "sheet",
}

def verify_motion_tokens(source: str, asset_json: str) -> list[str]:
    try:
        tokens = json.loads(asset_json).get("motionMs")
    except (json.JSONDecodeError, AttributeError, TypeError):
        return ["动效设计变量无法解析"]
    if not isinstance(tokens, dict) or set(tokens) != set(MOTION_KEYS) | {"reduced"}:
        return ["动效设计变量键集合缺失或多余"]
    match = re.search(r'\bobject\s+CodexMotion\s*\{(.*?)\n\}', source, re.S)
    if not match:
        return ["缺少 CodexMotion 定义"]
    actual = dict(re.findall(r'\bconst\s+val\s+(\w+)\s*=\s*(\d+)\b', match.group(1)))
    errors = []
    for name, constant in MOTION_KEYS.items():
        value = tokens[name]
        if type(value) is not int or value <= 0 or str(value) != actual.get(constant):
            errors.append(f"动效 {name} 与 CodexMotion.{constant} 不一致")
    if type(tokens["reduced"]) is not int or tokens["reduced"] != 0:
        errors.append("减少动效的设计时长必须为 0")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('--source', required=True, type=Path)
    parser.add_argument('--spec', required=True, type=Path)
    parser.add_argument('--tokens', type=Path)
    parser.add_argument('--motion', type=Path)
    args = parser.parse_args()
    source = args.source.read_text(encoding='utf-8')
    errors = verify(source, args.spec.read_text(encoding='utf-8'))
    if args.tokens is not None:
        asset_json = args.tokens.read_text(encoding='utf-8')
        errors += verify_asset_tokens(source, asset_json)
        if args.motion is None:
            errors.append('必须提供 CodexMotion 源码以核验动效设计变量')
        else:
            errors += verify_motion_tokens(args.motion.read_text(encoding='utf-8'), asset_json)
    if errors:
        for error in errors:
            print('设计色彩核验失败：' + error, file=sys.stderr)
        return 1
    print('设计色彩核验通过：全部 Ink 颜色和 Material 主色已与规范同步')
    return 0


if __name__ == '__main__':
    sys.exit(main())
