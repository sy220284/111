#!/usr/bin/env python3
"""Parse the pinned Rust app-server wire contract, keeping call direction and DTOs.

Only top-level protocol macro entries are accepted. Source DTOs and method-level
experimental tags are extracted without treating payload strings as RPC names.
"""
from __future__ import annotations

from dataclasses import dataclass
import re


@dataclass(frozen=True)
class MethodContract:
    name: str
    direction: str  # client_request or host_request
    params: str
    response: str
    experimental: bool


_METHOD = re.compile(
    r'(?m)^[ \t]*(?P<variant>[A-Za-z_]\w*)[ \t]*=>[ \t]*'
    r'"(?P<method>[A-Za-z][A-Za-z0-9_]*(?:/[A-Za-z0-9_]+)+)"[ \t]*\{'
    r'(?P<body>.*?)^[ \t]*\},?[ \t]*$', re.S | re.M,
)
_NOTIFICATION = re.compile(
    r'=>[ \t]*"([A-Za-z][A-Za-z0-9_]*(?:/[A-Za-z0-9_]+)+)"[ \t]*\('
)
_SERDE_NOTIFICATION = re.compile(
    r'(?m)^[ \t]*#\[serde\(rename[ \t]*=[ \t]*"([^"]+)"\)\]'
)
_FIELD = re.compile(r'(?m)^[ \t]*(params|response):[ \t]*(.+?)[ \t]*,[ \t]*$')


def _block(source: str, name: str) -> str:
    marker = re.search(rf'(?m)^[ \t]*{re.escape(name)}![ \t]*\{{', source)
    if marker is None:
        raise ValueError(f"官方协议缺少 {name} 宏")
    closing = re.search(r'(?m)^\}[ \t]*$', source[marker.end():])
    if closing is None:
        raise ValueError(f"官方协议的 {name} 宏未闭合")
    return source[marker.end():marker.end() + closing.start()]


def parse_contracts(source: str) -> dict[str, MethodContract]:
    result: dict[str, MethodContract] = {}
    for macro, direction in (
        ("client_request_definitions", "client_request"),
        ("server_request_definitions", "host_request"),
    ):
        body = _block(source, macro)
        previous = 0
        matches = list(_METHOD.finditer(body))
        if not matches:
            raise ValueError(f"{macro} 没有可解析的方法")
        for match in matches:
            name = match.group("method")
            if name in result:
                raise ValueError(f"官方协议重复方法：{name}")
            metadata = body[previous:match.start()]
            # Method-level #[experimental] can precede doc comments.
            experimental = bool(re.search(r'#\[experimental\(', metadata))
            fields = {m.group(1): m.group(2) for m in _FIELD.finditer(match.group("body"))}
            if "params" not in fields or "response" not in fields:
                raise ValueError(f"官方协议缺少参数/响应声明：{name}")
            result[name] = MethodContract(
                name=name,
                direction=direction,
                params=fields["params"],
                response=fields["response"],
                experimental=experimental,
            )
            previous = match.end()
    return result


def parse_notifications(source: str) -> set[str]:
    body = _block(source, "server_notification_definitions")
    return set(_NOTIFICATION.findall(body)) | set(_SERDE_NOTIFICATION.findall(body))


def request_method_names(source: str) -> tuple[set[str], set[str]]:
    contracts = parse_contracts(source)
    return (
        {name for name, c in contracts.items() if c.direction == "client_request"},
        {name for name, c in contracts.items() if c.direction == "host_request"},
    )
