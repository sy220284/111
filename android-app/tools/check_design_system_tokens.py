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
    found = list(TOKEN.finditer(source))
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
