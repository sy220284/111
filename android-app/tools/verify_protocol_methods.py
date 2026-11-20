#!/usr/bin/env python3
"""Cross-check Android's literal JSON-RPC method names against the bundled Codex protocol.

Requires no network, external packages, or generated global config.
Use the snapshot's codex-rs/app-server-protocol/src/protocol/common.rs as the authority.
"""
import argparse
from pathlib import Path
import re
import sys

# These are server notifications handled by UI state, plus one app-internal event.
# All other path-like literals in the Android transport and UI must exist in
# Codex's generated request protocol, so an upstream rename fails CI.
SERVER_NOTIFICATIONS = {
    "account/login/completed", "account/updated",
    "item/agentMessage/delta", "item/completed", "item/plan/delta",
    "item/reasoning/summaryTextDelta", "item/started",
    "serverRequest/resolved", "thread/archived", "thread/started",
    "turn/completed", "turn/diff/updated", "turn/started",
}
INTERNAL_EVENTS = {"local/unsupportedServerRequest"}
# Method-shaped enum variants from official v2/mcp.rs; these are payload
# discriminators, not request methods.
PROTOCOL_VARIANTS = {"openai/form", "openai/userVerification"}
METHOD_PATTERN = re.compile(r'\b[a-z][a-zA-Z0-9]+(?:/[a-zA-Z0-9]+)+\b')
EXPLICIT_METHOD = re.compile(r'=>\s*"([^"]+/[^"]+)"\s*\{')


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--protocol", type=Path, required=True)
    parser.add_argument("--sources", type=Path, required=True)
    args = parser.parse_args()
    if not args.protocol.is_file():
        parser.error(f"官方协议文件不存在：{args.protocol}")
    known = set(EXPLICIT_METHOD.findall(args.protocol.read_text(encoding="utf-8")))
    if len(known) < 100:
        parser.error(f"官方协议解析结果异常：只有 {len(known)} 个方法")
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
            for m in re.finditer(r'"([A-Za-z][A-Za-z0-9]+(?:/[A-Za-z0-9]+)+)"', line):
                method = m.group(1)
                if method in known:
                    used.add(method)
                elif (method not in SERVER_NOTIFICATIONS and method not in INTERNAL_EVENTS
                      and method not in PROTOCOL_VARIANTS):
                    unknown.append((file, number, method))
    print(f"已读取官方请求方法 {len(known)} 项；核查 Kotlin 文件 {len(source_files)} 个；使用到的有效请求 {len(used)} 项。")
    for file, number, method in unknown:
        print(f"协议未定义：{file}:{number}: {method}", file=sys.stderr)
    return 1 if unknown else 0


if __name__ == "__main__":
    sys.exit(main())
