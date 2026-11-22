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


CLIENT_DEFINITIONS = "client_request_definitions! {"
HOST_DEFINITIONS = "server_request_definitions! {"
NOTIFICATION_DEFINITIONS = "server_notification_definitions! {"
HOST_POLICY = re.compile(r'HOST_ONLY\s*=\s*setOf\((.*?)\)', re.DOTALL)


def protocol_directions(source: str) -> tuple[set[str], set[str]]:
    """Read client and Host methods from different official Rust macros."""
    client = source.find(CLIENT_DEFINITIONS)
    host = source.find(HOST_DEFINITIONS)
    notifications = source.find(NOTIFICATION_DEFINITIONS)
    if not (0 <= client < host < notifications):
        raise ValueError("官方协议请求方向宏结构不完整")
    return (
        set(EXPLICIT_METHOD.findall(source[client:host])),
        set(EXPLICIT_METHOD.findall(source[host:notifications]))
    )


def host_policy_methods(kotlin: str) -> set[str]:
    match = HOST_POLICY.search(kotlin)
    if match is None:
        raise ValueError("Android 缺少服务端主动请求列表")
    return set(re.findall(r'"([^"]+/[^"]+)"', match.group(1)))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--protocol", type=Path, required=True)
    parser.add_argument("--sources", type=Path, required=True)
    args = parser.parse_args()
    if not args.protocol.is_file():
        parser.error(f"官方协议文件不存在：{args.protocol}")
    original = args.protocol.read_text(encoding="utf-8")
    known, notifications = protocol_methods(original)
    try:
        client_methods, host_methods = protocol_directions(original)
    except ValueError as failure:
        parser.error(str(failure))
    if (len(client_methods) < 150 or len(host_methods) < 5
            or len(notifications) < 40 or client_methods | host_methods != known
            or client_methods & host_methods):
        parser.error("官方协议请求方向或通知解析异常，请核对上游结构")
    source_files = sorted(args.sources.rglob("*.kt"))
    if not source_files:
        parser.error("未找到 Kotlin 源文件")

    application = args.sources / "io/github/sy220284/codexandroid"
    policy_file = application / "ProtocolDirectionPolicy.kt"
    connection_file = application / "CodexConnection.kt"
    if not policy_file.is_file() or not connection_file.is_file():
        parser.error("缺少客户端请求方向或连接层代码")
    try:
        guarded = host_policy_methods(policy_file.read_text(encoding="utf-8"))
    except ValueError as failure:
        parser.error(str(failure))
    if guarded != host_methods:
        print("Android Host-only 方法表与官方协议不一致：" +
              f"缺失 {sorted(host_methods - guarded)}，多出 {sorted(guarded - host_methods)}",
              file=sys.stderr)
        return 1
    connection = connection_file.read_text(encoding="utf-8")
    request_body = connection.split("suspend fun request(", 1)[-1].split(
        "suspend fun notify(", 1)[0]
    notify_body = connection.split("suspend fun notify(", 1)[-1].split(
        "suspend fun answer(", 1)[0]
    guard_call = "ProtocolDirectionPolicy.requireClientSend(method)"
    if (guard_call not in request_body or guard_call not in notify_body):
        print("客户端请求或通知的实际发送链路缺少 Host-only 方向拦截", file=sys.stderr)
        return 1
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
    print(f"已核对官方客户端请求 {len(client_methods)} 项、Host 主动请求 {len(host_methods)} 项、" +
          f"通知 {len(notifications)} 项；核查 Kotlin 文件 {len(source_files)} 个；" +
          f"源码引用的有效请求 {len(used)} 项，Host-only 方向拦截已接入发送链路。")
    for file, number, method in unknown:
        print(f"协议未定义：{file}:{number}: {method}", file=sys.stderr)
    return 1 if unknown else 0


if __name__ == "__main__":
    sys.exit(main())
