#!/usr/bin/env python3
"""Check stable client DTO exports and experimental project association.

Experimental methods are intentionally absent from the stable v2 JSON schema;
the Rust type is authoritative for their fields and gated capabilities.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys

from protocol_contract import parse_contracts

V2_TYPE = re.compile(r'^v2::([A-Za-z]\w+)$')


def verify(source: str, schema_dir: Path, thread_source: str,
           project_contract: str, connection_source: str,
           turn_source: str, turn_contract: str) -> tuple[list[str], dict]:
    methods = parse_contracts(source)
    errors: list[str] = []
    stable_dtos = 0
    experimental_client = 0
    host = 0
    for method in methods.values():
        if method.direction == "host_request":
            host += 1
            continue
        if method.experimental:
            experimental_client += 1
            continue
        for key in ("params", "response"):
            value = getattr(method, key)
            match = V2_TYPE.fullmatch(value)
            if match is None:
                continue
            name = match.group(1)
            schema_file = schema_dir / (name + ".json")
            if not schema_file.is_file():
                errors.append(f"稳定方法 {method.name} 的{key}缺少 v2 Schema：{name}")
                continue
            try:
                payload = json.loads(schema_file.read_text(encoding="utf-8"))
            except (OSError, ValueError) as exc:
                errors.append(f"Schema 无法读取：{name}：{exc}")
                continue
            if not isinstance(payload, dict) or (
                    "properties" not in payload and "$ref" not in payload
                    and "oneOf" not in payload and "anyOf" not in payload
                    and "type" not in payload):
                errors.append(f"Schema 结构不完整：{name}")
                continue
            stable_dtos += 1

    experimental_field = '#[experimental("thread/start.projectId")]'
    if experimental_field not in thread_source or (
            "pub project_id: Option<String>" not in thread_source):
        errors.append("Rust ThreadStartParams 缺少实验性 projectId 字段")
    stable_start = schema_dir / "ThreadStartParams.json"
    if not stable_start.is_file():
        errors.append("缺少稳定 ThreadStartParams Schema")
    else:
        fields = json.loads(stable_start.read_text(encoding="utf-8")).get("properties", {})
        if "projectId" in fields:
            errors.append("实验字段 projectId 意外进入稳定 ThreadStartParams Schema")
    if 'put("projectId", projectId)' not in project_contract:
        errors.append("Android 项目请求构造器未携带 projectId")
    turn_experimental = '#[experimental("turn/start.collaborationMode")]'
    if turn_experimental not in turn_source or (
            "pub collaboration_mode: Option<CollaborationMode>" not in turn_source):
        errors.append("Rust TurnStartParams 缺少实验性 collaborationMode 字段")
    stable_turn = schema_dir / "TurnStartParams.json"
    if not stable_turn.is_file():
        errors.append("缺少稳定 TurnStartParams Schema")
    else:
        turn_fields = json.loads(stable_turn.read_text(encoding="utf-8")).get("properties", {})
        if "collaborationMode" in turn_fields:
            errors.append("实验字段 collaborationMode 意外进入稳定 TurnStartParams Schema")
    if 'args.put("collaborationMode",' not in turn_contract:
        errors.append("Android 计划模式缺少 collaborationMode 适配")

    if 'Initialize => "initialize" {' not in source or (
            'client_notification_definitions! {' not in source):
        errors.append("官方初始化握手声明缺失")
    initialize = connection_source.find('requestForSession("initialize",')
    initialized = connection_source.find('write(epoch, JSONObject().put("method", "initialized")')
    ready = connection_source.find('ownership.markReady(epoch)')
    if min(initialize, initialized, ready) == -1 or not initialize < initialized < ready:
        errors.append("Android 必须在同一连接完成 initialize 请求、initialized 通知后才开放普通请求")

    if '.put("experimentalApi", true)' not in connection_source:
        errors.append("Android 未声明实验协议能力")

    return errors, {
        "methods": len(methods),
        "stableClientDtoSchemas": stable_dtos,
        "experimentalClientMethods": experimental_client,
        "hostRequestMethods": host,
        "projectAssociationExperimental": True if not errors else False,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--protocol", type=Path, required=True)
    parser.add_argument("--schemas", type=Path, required=True)
    parser.add_argument("--thread-types", type=Path, required=True)
    parser.add_argument("--turn-types", type=Path, required=True)
    parser.add_argument("--android-project", type=Path, required=True)
    parser.add_argument("--android-turn", type=Path, required=True)
    parser.add_argument("--android-connection", type=Path, required=True)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    errors, stats = verify(
        args.protocol.read_text(encoding="utf-8"), args.schemas,
        args.thread_types.read_text(encoding="utf-8"),
        args.android_project.read_text(encoding="utf-8"),
        args.android_connection.read_text(encoding="utf-8"),
        args.turn_types.read_text(encoding="utf-8"),
        args.android_turn.read_text(encoding="utf-8"),
    )
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps({"statistics": stats, "errors": errors},
                                          ensure_ascii=False, indent=2) + "\n",
                               encoding="utf-8")
    for message in errors:
        print("协议字段校验失败：" + message, file=sys.stderr)
    print(f"已校验稳定客户端 DTO 类型引用 {stats['stableClientDtoSchemas']} 处；"
          f"实验方法 {stats['experimentalClientMethods']} 项；Host {stats['hostRequestMethods']} 项")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
