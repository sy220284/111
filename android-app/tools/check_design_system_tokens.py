#!/usr/bin/env python3
"""Validate the documented Android/Figma palette against actual Compose Ink tokens."""
from __future__ import annotations
import argparse
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
        match = re.search(r'\\bobject\\s+' + re.escape(name) + r'\\s*\\{(.*?)\\n\\}', source, re.S)
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
            getter = (r'\\bval\\s+' + re.escape(name) +
                r'\\s*:\\s*Color\\s+get\\(\\)\\s*=\\s*if\\s*\\(UiPreferences\\.darkMode\\.value\\)')
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
    if not re.search(r'\bprimary\s*=\s*Ink\.coral\b', source):
        errors.append('MaterialTheme.primary 应映射 Ink.coral')
    if not re.search(r'\bonPrimary\s*=\s*Ink\.canvas\b', source):
        errors.append('MaterialTheme.onPrimary 应映射 Ink.canvas')
    for match in declared:
        if not match.group(3).strip():
            errors.append(f'Ink.{match.group(1)} 缺少使用说明')
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('--source', required=True, type=Path)
    parser.add_argument('--spec', required=True, type=Path)
    args = parser.parse_args()
    errors = verify(args.source.read_text(encoding='utf-8'), args.spec.read_text(encoding='utf-8'))
    if errors:
        for error in errors:
            print('设计色彩核验失败：' + error, file=sys.stderr)
        return 1
    print('设计色彩核验通过：全部 Ink 颜色和 Material 主色已与规范同步')
    return 0


if __name__ == '__main__':
    sys.exit(main())
