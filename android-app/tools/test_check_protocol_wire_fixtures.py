#!/usr/bin/env python3
"""Regression tests for real Kotlin RPC fixture versus Rust DTO validation."""
from pathlib import Path
from tempfile import TemporaryDirectory
import json
import unittest

from jsonschema import Draft7Validator

from check_protocol_wire_fixtures import (
    project_schema_for, schema_inventory, verify, check_local_refs
)

ROOT = Path(__file__).resolve().parents[2]
PROJECT_RUST = ROOT / "upstream/codex/codex-rs/app-server-protocol/src/protocol/v2/project.rs"
SOURCE = """
client_request_definitions! {
    Start => "thread/start" {
        params: v2::ThreadStartParams,
        response: v2::ThreadStartResponse,
    },
}
server_request_definitions! {
    Host => "item/tool/call" {
        params: v2::DynamicToolCallParams,
        response: v2::DynamicToolCallResponse,
    },
}
"""


class WireFixturesTest(unittest.TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.schemas = Path(self.temp.name)
        self.schema = {"type": "object", "properties": {
            "cwd": {"type": "string"},
            "model": {"enum": ["gpt-6", "gpt-5.6"], "type": "string"}
        }, "required": ["cwd"], "additionalProperties": False}
        (self.schemas / "ThreadStartParams.json").write_text(
            json.dumps(self.schema), encoding="utf-8")
        (self.schemas / "ThreadStartResponse.json").write_text(
            json.dumps({"type": "object", "properties": {
                "thread": {"type": "object"}
            }, "required": ["thread"]}), encoding="utf-8")

    def check(self, params, method="thread/start"):
        source = json.dumps({"version": 1,
                             "source": "Kotlin RequestContract builders executed by JUnit",
                             "fixtures": [{"method": method, "case": "fixture", "params": params}]})
        return verify(SOURCE, self.schemas, source, "", "", "")

    def test_real_valid_builder_payload_passes_required_and_enum(self):
        errors, report = self.check({"cwd": "/data/work", "model": "gpt-6"})
        self.assertEqual([], errors)
        self.assertEqual(2, report["stableDtoDocumentsStructurallyChecked"])
        self.assertEqual(1, report["fixtureCoveredClientMethods"])
        self.assertGreaterEqual(report["negativeValidationChecks"], 2)
        self.assertEqual(0, report["realRustCoreIntegrationTests"])

    def test_missing_required_field_is_detected(self):
        errors, _ = self.check({"model": "gpt-6"})
        self.assertTrue(any("required property" in e for e in errors), errors)

    def test_wrong_type_and_enum_are_detected(self):
        errors, _ = self.check({"cwd": 7, "model": "unknown"})
        self.assertTrue(any("cwd" in e for e in errors), errors)
        self.assertTrue(any("model" in e for e in errors), errors)

    def test_unknown_field_cannot_be_silently_ignored(self):
        errors, _ = self.check({"cwd": "/data/work", "notInRust": 1})
        self.assertTrue(any("未知的顶层请求字段" in e for e in errors), errors)

    def test_host_method_is_not_a_client_request(self):
        errors, _ = self.check({}, method="item/tool/call")
        self.assertTrue(any("不是官方 Android" in e for e in errors), errors)

    def test_dangling_reference_is_detected(self):
        schema = {"type": "object",
                  "properties": {"child": {"$ref": "#/definitions/Missing"}}}
        self.assertTrue(any("引用不存在" in e
                            for e in check_local_refs(schema, schema, "test")))

    def test_pinned_experimental_project_fields_and_required_types(self):
        rust = PROJECT_RUST.read_text(encoding="utf-8")
        create = project_schema_for("ProjectCreateParams", rust)
        import_schema = project_schema_for("ProjectImportParams", rust)
        move = project_schema_for("ProjectMoveParams", rust)
        self.assertEqual({"name", "roots", "idempotencyKey"},
                         set(create["required"]))
        self.assertTrue(Draft7Validator(create).is_valid({
            "name": "work", "roots": [{"path": "/tmp/work"}],
            "idempotencyKey": "one"}))
        self.assertFalse(Draft7Validator(create).is_valid({
            "name": "work", "roots": [{"path": 8}], "idempotencyKey": "one"}))
        self.assertFalse(Draft7Validator(import_schema).is_valid({
            "name": "work", "threads": [7], "roots": [],
            "idempotencyKey": "two"}))
        self.assertTrue(Draft7Validator(move).is_valid({
            "projectId": "one", "beforeProjectId": None}))
        self.assertFalse(Draft7Validator(move).is_valid({
            "projectId": "one", "beforeProjectId": False}))

    def test_stable_schema_inventory_has_no_hidden_missing_ref(self):
        from protocol_contract import parse_contracts
        self.schema["properties"]["cwd"] = {"$ref": "#/definitions/Unknown"}
        (self.schemas / "ThreadStartParams.json").write_text(
            json.dumps(self.schema), encoding="utf-8")
        _, errors = schema_inventory(parse_contracts(SOURCE), self.schemas)
        self.assertTrue(any("引用不存在" in error for error in errors), errors)


if __name__ == "__main__":
    unittest.main()
