#!/usr/bin/env python3
"""Validate Kotlin-executed RPC requests against the pinned Rust wire contract.

Unlike the method-name/DTO-file audit, this consumes JSON emitted by JUnit
directly from Android RequestContract builders. Stable DTOs use the official
draft-07 JSON Schema. Experimental project DTOs are derived from pinned Rust
struct fields; their limitations are reported separately.
"""
from __future__ import annotations

import argparse
from copy import deepcopy
import json
from pathlib import Path
import re
import sys
from typing import Any

from jsonschema import Draft7Validator, FormatChecker
from protocol_contract import parse_contracts

V2_TYPE = re.compile(r"^v2::([A-Za-z]\w+)$")
PROJECT_STRUCT = re.compile(
    r"(?ms)^pub struct (?P<name>Project[A-Za-z]+Params|ProjectRoot)\s*\{(?P<body>.*?)^\}"
)
RUST_FIELD = re.compile(r"(?m)^[ \t]*pub ([a-z]\w*)[ \t]*:[ \t]*(.+),[ \t]*$")
EXPERIMENTAL_FIELDS = {
    "thread/start": {"projectId"},
    "turn/start": {"collaborationMode"},
}
PROJECT_METHODS = {
    "project/create", "project/import", "project/read",
    "project/update", "project/move",
}


def camel(name: str) -> str:
    return re.sub(r"_([a-z])", lambda match: match.group(1).upper(), name)


def project_schema_for(type_name: str, rust: str) -> dict:
    blocks = {m.group("name"): m.group("body") for m in PROJECT_STRUCT.finditer(rust)}
    if type_name not in blocks:
        raise ValueError(f"锁定的 Rust project.rs 中缺失 {type_name}")
    body = blocks[type_name]
    fields = RUST_FIELD.findall(body)
    if not fields:
        raise ValueError(f"Rust {type_name} 无可解析的字段")

    def field_schema(rust_type: str) -> dict:
        kind = rust_type.strip()
        if kind.startswith("Option<") and kind.endswith(">"):
            return {"anyOf": [field_schema(kind[7:-1]), {"type": "null"}]}
        if kind.startswith("Vec<") and kind.endswith(">"):
            return {"type": "array", "items": field_schema(kind[4:-1])}
        if kind == "ProjectRoot":
            return project_schema_for("ProjectRoot", rust)
        if kind == "BTreeMap<String, String>":
            return {"type": "object", "additionalProperties": {"type": "string"}}
        if kind in {"String", "AbsolutePathBuf"}:
            return {"type": "string"}
        if kind in {"u32", "u64", "i64"}:
            return {"type": "integer"}
        raise ValueError(f"实验 Rust 字段类型尚无明确校验规则：{type_name}.{kind}")

    props = {camel(name): field_schema(kind) for name, kind in fields}
    required = [camel(name) for name, kind in fields if not kind.startswith("Option<")]
    return {"type": "object", "properties": props, "required": required,
            "additionalProperties": False}


def check_local_refs(schema: Any, root: Any, label: str) -> list[str]:
    errors: list[str] = []
    if isinstance(schema, dict):
        ref = schema.get("$ref")
        if ref is not None:
            if not isinstance(ref, str) or not ref.startswith("#/"):
                errors.append(f"{label} 不支持未经验证的外部 $ref：{ref}")
            else:
                value = root
                try:
                    for part in ref[2:].split("/"):
                        value = value[part.replace("~1", "/").replace("~0", "~")]
                except (KeyError, TypeError, IndexError):
                    errors.append(f"{label} 引用不存在：{ref}")
        for key, value in schema.items():
            if key != "$ref":
                errors.extend(check_local_refs(value, root, label))
    elif isinstance(schema, list):
        for part in schema:
            errors.extend(check_local_refs(part, root, label))
    return errors


def schema_inventory(contracts: dict, directory: Path) -> tuple[dict[str, Any], list[str]]:
    schemas: dict[str, Any] = {}
    errors: list[str] = []
    for contract in contracts.values():
        if contract.direction != "client_request" or contract.experimental:
            continue
        for kind in ("params", "response"):
            match = V2_TYPE.fullmatch(getattr(contract, kind))
            if match is None:
                continue
            name = match.group(1)
            if name in schemas:
                continue
            file = directory / (name + ".json")
            if not file.is_file():
                errors.append(f"官方稳定 DTO 文件缺失：{contract.name}.{kind} ({name})")
                continue
            try:
                schema = json.loads(file.read_text(encoding="utf-8"))
                Draft7Validator.check_schema(schema)
                errors.extend(check_local_refs(schema, schema, name))
                schemas[name] = schema
            except (OSError, ValueError, TypeError) as exc:
                errors.append(f"官方 DTO 校验失败 {name}: {exc}")
    return schemas, errors


def check_experimental_field(method: str, key: str, value: Any,
                             stable_schema: dict, thread_rust: str,
                             turn_rust: str) -> list[str]:
    if method == "thread/start" and key == "projectId":
        if '#[experimental("thread/start.projectId")]' not in thread_rust:
            return ["Rust 未声明实验性 thread/start.projectId"]
        if not isinstance(value, str) or not value:
            return ["thread/start.projectId 需要非空字符串"]
        return []
    if method == "turn/start" and key == "collaborationMode":
        if '#[experimental("turn/start.collaborationMode")]' not in turn_rust:
            return ["Rust 未声明实验性 turn/start.collaborationMode"]
        defs = stable_schema.get("definitions", {})
        if "CollaborationMode" not in defs:
            return ["Rust 稳定 TurnStartParams Schema 缺少复用的 CollaborationMode 定义"]
        nested = {"$schema": "http://json-schema.org/draft-07/schema#",
                  "definitions": defs, "$ref": "#/definitions/CollaborationMode"}
        return [
            f"turn/start.collaborationMode：{error.message}"
            for error in Draft7Validator(nested, format_checker=FormatChecker()).iter_errors(value)
        ]
    return [f"{method} 未登记实验字段 {key}"]


def verify(protocol: str, schema_dir: Path, fixture_source: str,
           project_rust: str, thread_rust: str, turn_rust: str) -> tuple[list[str], dict]:
    contracts = parse_contracts(protocol)
    errors: list[str] = []
    schemas, inventory_errors = schema_inventory(contracts, schema_dir)
    errors.extend(inventory_errors)
    fixture = json.loads(fixture_source)
    if fixture.get("version") != 1 or not isinstance(fixture.get("fixtures"), list):
        raise ValueError("真实构造样例文件缺少 version=1 / fixtures 数组")
    if fixture.get("source") != "Kotlin RequestContract builders executed by JUnit":
        raise ValueError("样例来源必须由 Kotlin JUnit 构造器导出")
    names: set[tuple[str, str]] = set()
    covered: set[str] = set()
    negative = 0
    checked_fields = 0
    experimental_cases = 0
    for item in fixture["fixtures"]:
        if not isinstance(item, dict):
            errors.append("样例必须是结构化对象")
            continue
        method = item.get("method")
        case = item.get("case")
        params = item.get("params")
        if not isinstance(method, str) or not isinstance(case, str) or not case:
            errors.append(f"缺少方法名或场景名：{item}")
            continue
        if (method, case) in names:
            errors.append(f"重复的真实构造样例：{method}/{case}")
            continue
        names.add((method, case))
        contract = contracts.get(method)
        if contract is None or contract.direction != "client_request":
            errors.append(f"样例请求不是官方 Android→Rust 方法：{method}")
            continue
        if not isinstance(params, dict):
            errors.append(f"{method}/{case} 需要 JSON 对象请求参数")
            continue
        match = V2_TYPE.fullmatch(contract.params)
        if match is None:
            errors.append(f"{method}/{case} 的 Rust 请求类型未明确定义：{contract.params}")
            continue
        name = match.group(1)
        try:
            if method in PROJECT_METHODS and contract.experimental:
                schema = project_schema_for(name, project_rust)
                experimental_cases += 1
            else:
                schema = schemas.get(name)
                if schema is None:
                    errors.append(f"{method}/{case} 无可用官方请求 Schema：{name}")
                    continue
        except ValueError as exc:
            errors.append(f"{method}/{case}: {exc}")
            continue

        allowed = EXPERIMENTAL_FIELDS.get(method, set())
        extra = set(params) - set(schema.get("properties", {}))
        for field in sorted(extra):
            if field not in allowed:
                errors.append(f"{method}/{case} 存在未知的顶层请求字段：{field}")
            else:
                errors.extend(check_experimental_field(
                    method, field, params[field], schema, thread_rust, turn_rust))
        stable = {key: value for key, value in params.items() if key not in allowed}
        validator = Draft7Validator(schema, format_checker=FormatChecker())
        for err in validator.iter_errors(stable):
            path = ".".join(str(part) for part in err.absolute_path) or "(root)"
            errors.append(f"{method}/{case} {path}: {err.message}")
        if not validator.is_valid(stable):
            continue
        covered.add(method)
        checked_fields += len(params)
        required = schema.get("required", [])
        if required:
            bad = deepcopy(stable)
            bad.pop(required[0], None)
            if validator.is_valid(bad):
                errors.append(f"{method}/{case} 必填字段缺失负向测试未被 Schema 阻断")
            else:
                negative += 1
        # Guard the type checker against permissively accepting a wrong field
        # shape; only mutate fields whose official schema rejects the mutation.
        for key in stable:
            candidate = deepcopy(stable)
            candidate[key] = {"_invalid_type": True}
            if not validator.is_valid(candidate):
                negative += 1
                break

    if not names:
        errors.append("未导出任何真实 Kotlin 请求构造器样例")
    all_client = {m.name for m in contracts.values() if m.direction == "client_request"}
    report = {
        "officialMethods": len(contracts),
        "stableDtoDocumentsStructurallyChecked": len(schemas),
        "exportedKotlinFixtureCases": len(names),
        "fixtureCoveredClientMethods": len(covered),
        "fixtureCoveredMethodNames": sorted(covered),
        "clientMethodsWithoutBuilderFixtures": sorted(all_client - covered),
        "validatedTopLevelFieldsInSamples": checked_fields,
        "experimentalRustProjectCases": experimental_cases,
        "negativeValidationChecks": negative,
        "realRustCoreIntegrationTests": 0,
        "hostResponseRuntimeTests": 0,
        "errors": errors,
    }
    return errors, report


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--protocol", type=Path, required=True)
    parser.add_argument("--schemas", type=Path, required=True)
    parser.add_argument("--fixtures", type=Path, required=True)
    parser.add_argument("--project-rust", type=Path, required=True)
    parser.add_argument("--thread-rust", type=Path, required=True)
    parser.add_argument("--turn-rust", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    errors, result = verify(
        args.protocol.read_text(encoding="utf-8"), args.schemas,
        args.fixtures.read_text(encoding="utf-8"),
        args.project_rust.read_text(encoding="utf-8"),
        args.thread_rust.read_text(encoding="utf-8"),
        args.turn_rust.read_text(encoding="utf-8"))
    args.report.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n",
                           encoding="utf-8")
    for error in errors:
        print("实际 Kotlin 请求与 Rust 字段契约不一致：" + error, file=sys.stderr)
    print(f"稳定 DTO 文档结构校验 {result['stableDtoDocumentsStructurallyChecked']} 项；"
          f" Kotlin 请求样例 {result['exportedKotlinFixtureCases']} 个、"
          f" 覆盖方法 {result['fixtureCoveredClientMethods']} 种、"
          f" 负向验证 {result['negativeValidationChecks']} 次。")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
