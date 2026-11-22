#!/usr/bin/env python3
"""Guard against high-risk thousand-line Android Kotlin UI/controller files."""
import argparse
from pathlib import Path
import sys


def oversized_kotlin_files(folder: Path, maximum: int) -> list[tuple[str, int]]:
    return [
        (str(path.relative_to(folder)), sum(1 for _ in path.open(encoding="utf-8")))
        for path in sorted(folder.rglob("*.kt"))
        if sum(1 for _ in path.open(encoding="utf-8")) > maximum
    ]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sources", type=Path, required=True)
    parser.add_argument("--max-lines", type=int, default=760)
    args = parser.parse_args()
    if not args.sources.is_dir() or args.max_lines < 1:
        parser.error("源码目录不存在或行数限制非法")
    files = sorted(args.sources.rglob("*.kt"))
    if not files:
        parser.error("源码目录内不存在 Kotlin 文件")
    failures = []
    for path in files:
        with path.open(encoding="utf-8") as handle:
            count = sum(1 for _ in handle)
        if count > args.max_lines:
            failures.append((path.relative_to(args.sources), count))
    print(f"检查 Kotlin 文件 {len(files)} 个；单文件预算 {args.max_lines} 行")
    for path, count in failures:
        print(f"超出预算：{path} 共 {count} 行", file=sys.stderr)
    return int(bool(failures))


if __name__ == "__main__":
    sys.exit(main())
