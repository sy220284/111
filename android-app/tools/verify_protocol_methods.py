#!/usr/bin/env python3
"""Cross-check Android's literal JSON-RPC method names against the bundled Codex protocol.

Requires no network, external packages, or generated global config.
Use the snapshot's codex-rs/app-server-protocol/src/protocol/common.rs as the authority.
"""
import argparse
import mimetypes
from pathlib import Path
import re
import sys

# Both client requests and server notifications come from the pinned upstream
# protocol. This avoids rejecting a real notification when new UI events are wired.
INTERNAL_EVENTS = {"local/unsupportedServerRequest"}
# Method-shaped enum variants from official v2/mcp.rs; these are payload
# discriminators, not request methods.
PROTOCOL_VARIANTS = {"openai/form", "openai/userVerification"}
# All standard MIME types (e.g. text/plain, application/json, image/webp) are
# content formats, not app-server methods. Use the Python standard-library type
# registry so adding a resource reader never requires another hard-coded exception.
def is_standard_mime(value: str) -> bool:
    return mimetypes.guess_extension(value, strict=False) is not None
METHOD_PATTERN = re.compile(r'\b[a-z][a-zA-Z0-9_]+(?:/[a-zA-Z0-9_]+)+\b')
EXPLICIT_METHOD = re.compile(r'=>\s*"([^"]+/[^"]+)"\s*\{')
NOTIFICATION_METHOD = re.compile(r'=>\s*"([^"]+/[^"]+)"\s*\(')
# A few official notifications use explicit serde attributes rather than the
# normal macro wire-name arrow (for example account/login/completed).
SERDE_NOTIFICATION = re.compile(r'(?m)^\s*#\[serde\(rename\s*=\s*"([^"]+/[^"]+)"\)\]')


def protocol_methods(source: str) -> tuple[set[str], set[str]]:
    """Parse two distinct official protocol macro shapes."""
    return set(EXPLICIT_METHOD.findall(source)), (
        set(NOTIFICATION_METHOD.findall(source)) |
        set(SERDE_NOTIFICATION.findall(source))
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--protocol", type=Path, required=True)
    parser.add_argument("--sources", type=Path, required=True)
    args = parser.parse_args()
    if not args.protocol.is_file():
        parser.error(f"官方协议文件不存在：{args.protocol}")
    known, notifications = protocol_methods(args.protocol.read_text(encoding="utf-8"))
    if len(known) < 100 or len(notifications) < 40:
        parser.error(f"官方协议解析结果异常：请求 {len(known)} 项，通知 {len(notifications)} 项")
    source_files = sorted(args.sources.rglob("*.kt"))
    if not source_files:
        parser.error("未找到 Kotlin 源文件")
    unknown = []
    used = set()
    for file in source_files:
        for number, line in enumerate(file.read_text(encoding="utf-8").splitlines(), start=1):
            # Kotlin source may contain file-system paths, but a literal string
            # matching a Codex RPC name always starts with an identifier and
            # contains a slash. Avoid scraping arbitrary free-form prose.
            for m in re.finditer(r'"([A-Za-z][A-Za-z0-9_]+(?:/[A-Za-z0-9_]+)+)"', line):
                method = m.group(1)
                if method in known:
                    used.add(method)
                elif (method not in notifications and method not in INTERNAL_EVENTS
                      and method not in PROTOCOL_VARIANTS
                      and not is_standard_mime(method)):
                    unknown.append((file, number, method))
    print(f"已读取官方请求 {len(known)} 项、通知 {len(notifications)} 项；核查 Kotlin 文件 {len(source_files)} 个；使用到的有效请求 {len(used)} 项。")
    for file, number, method in unknown:
        print(f"协议未定义：{file}:{number}: {method}", file=sys.stderr)
    return 1 if unknown else 0


if __name__ == "__main__":
    sys.exit(main())
