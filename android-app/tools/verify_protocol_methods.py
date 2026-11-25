#!/usr/bin/env python3
"""Check Android RPC literals against the pinned Rust wire contract.

Validates method existence AND call direction. Notifications and MIME values
are recognized separately from methods that Android may request.
"""
from __future__ import annotations

import argparse
import mimetypes
from pathlib import Path
import re
import sys

from protocol_contract import parse_contracts, parse_notifications

INTERNAL_EVENTS = {"local/unsupportedServerRequest"}
PROTOCOL_VARIANTS = {"openai/form", "openai/userVerification"}
EXPLICIT_METHOD = re.compile(r'=>\s*"([^"]+/[^"]+)"\s*\{')
NOTIFICATION_METHOD = re.compile(r'=>\s*"([^"]+/[^"]+)"\s*\(')
SERDE_NOTIFICATION = re.compile(r'(?m)^\s*#\[serde\(rename\s*=\s*"([^"]+/[^"]+)"\)\]')
LITERAL = re.compile(r'"([A-Za-z][A-Za-z0-9_]+(?:/[A-Za-z0-9_]+)+)"')
CALL = re.compile(
    r'\b(?:(?:request|capabilityRequest|requestForSession)\s*\(\s*|(?:rpc|refresh|ensureCatalog)\s*\(\s*'
    r'(?:[A-Za-z_][A-Za-z0-9_]*|"[^"\n]*")\s*,\s*)'
    r'"(?P<method>[A-Za-z][A-Za-z0-9_]+(?:/[A-Za-z0-9_]+)+)"',
)


def protocol_methods(source: str) -> tuple[set[str], set[str]]:
    """Compatibility helper used by tests for raw macro-fragment parsing."""
    return set(EXPLICIT_METHOD.findall(source)), (
        set(NOTIFICATION_METHOD.findall(source)) | set(SERDE_NOTIFICATION.findall(source))
    )


def is_standard_mime(value: str) -> bool:
    return mimetypes.guess_extension(value, strict=False) is not None


def verify_sources(source: str, files: dict[str, str]) -> tuple[list[str], set[str]]:
    contracts = parse_contracts(source)
    notifications = parse_notifications(source)
    errors: list[str] = []
    used: set[str] = set()
    namespaces = {name.split("/", 1)[0] for name in contracts.keys() | notifications}
    for file, code in sorted(files.items()):
        calls = list(CALL.finditer(code))
        explicit_calls = {(code.count("\n", 0, match.start("method")) + 1, match.group("method"))
                          for match in calls}
        for line_number, line in enumerate(code.splitlines(), start=1):
            for match in LITERAL.finditer(line):
                method = match.group(1)
                if method in contracts:
                    used.add(method)
                elif ((method.split("/", 1)[0] in namespaces or (line_number, method) in explicit_calls)
                      and method not in notifications
                      and method not in INTERNAL_EVENTS
                      and method not in PROTOCOL_VARIANTS
                      and not is_standard_mime(method)):
                    errors.append(f"协议未定义：{file}:{line_number}: {method}")
        # Only explicit call-sites are direction checks. Bare literals can be
        # metadata, state filters or service-originated requests.
        for match in calls:
            name = match.group("method")
            contract = contracts.get(name)
            if contract is not None and contract.direction != "client_request":
                line = code.count("\n", 0, match.start()) + 1
                errors.append(f"请求方向错误：{file}:{line}: {name} 是服务端→Android Host 请求")
            elif name in notifications:
                line = code.count("\n", 0, match.start()) + 1
                errors.append(f"通知不能作为请求：{file}:{line}: {name}")
    return errors, used


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--protocol", type=Path, required=True)
    parser.add_argument("--sources", type=Path, required=True)
    args = parser.parse_args()
    if not args.protocol.is_file():
        parser.error(f"官方协议文件不存在：{args.protocol}")
    files = {str(path): path.read_text(encoding="utf-8")
             for path in sorted(args.sources.rglob("*.kt"))}
    if not files:
        parser.error("未找到 Kotlin 源文件")
    source = args.protocol.read_text(encoding="utf-8")
    try:
        contracts = parse_contracts(source)
        notifications = parse_notifications(source)
        errors, used = verify_sources(source, files)
    except ValueError as exc:
        parser.error(str(exc))
    client = sum(c.direction == "client_request" for c in contracts.values())
    host = len(contracts) - client
    print(f"官方协议客户端请求 {client} 项，Host 请求 {host} 项，通知 {len(notifications)} 项；"
          f"核查 Kotlin 文件 {len(files)} 个，方法字面量 {len(used)} 项。")
    for error in errors:
        print(error, file=sys.stderr)
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
