#!/usr/bin/env python3
import unittest
from pathlib import Path

from check_protocol_schema_alignment import verify

ROOT = Path(__file__).resolve().parents[2]
UPSTREAM = ROOT / "upstream/codex/codex-rs/app-server-protocol"
ANDROID = ROOT / "android-app/app/src/main/java/io/github/sy220284/codexandroid"


class SchemaAlignmentTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.protocol = (UPSTREAM / "src/protocol/common.rs").read_text(encoding="utf-8")
        cls.schemas = UPSTREAM / "schema/json/v2"
        cls.thread = (UPSTREAM / "src/protocol/v2/thread.rs").read_text(encoding="utf-8")
        cls.project = (ANDROID / "ProjectRequestContract.kt").read_text(encoding="utf-8")
        cls.turn = (UPSTREAM / "src/protocol/v2/turn.rs").read_text(encoding="utf-8")
        cls.turn_contract = (ANDROID / "TurnRequestContract.kt").read_text(encoding="utf-8")
        cls.connection = (ANDROID / "CodexConnection.kt").read_text(encoding="utf-8")

    def test_stable_dtos_and_experimental_project_field(self):
        errors, stats = verify(self.protocol, self.schemas, self.thread,
                               self.project, self.connection, self.turn, self.turn_contract)
        self.assertEqual([], errors)
        self.assertEqual(195, stats["methods"])
        self.assertEqual(204, stats["stableClientDtoSchemas"])
        self.assertEqual(8, stats["hostRequestMethods"])

    def test_missing_android_project_binding_is_detected(self):
        errors, _ = verify(self.protocol, self.schemas, self.thread,
                           self.project.replace('put("projectId", projectId)', ''),
                           self.connection, self.turn, self.turn_contract)
        self.assertTrue(any("未携带 projectId" in e for e in errors))

    def test_plan_mode_experimental_binding_is_verified(self):
        errors, _ = verify(self.protocol, self.schemas, self.thread,
                           self.project, self.connection, self.turn,
                           self.turn_contract.replace('args.put("collaborationMode",', 'args.put("renamed",'))
        self.assertTrue(any("缺少 collaborationMode" in e for e in errors))

    def test_initialization_handshake_order_is_enforced(self):
        broken = self.connection.replace('notify("initialized",', 'notify("removed",')
        errors, _ = verify(self.protocol, self.schemas, self.thread,
                           self.project, broken, self.turn, self.turn_contract)
        self.assertTrue(any("initialize" in e and "initialized" in e for e in errors))

    def test_missing_experimental_capability_is_detected(self):
        errors, _ = verify(self.protocol, self.schemas, self.thread,
                           self.project,
                           self.connection.replace('.put("experimentalApi", true)', ''),
                           self.turn, self.turn_contract)
        self.assertTrue(any("未声明实验协议能力" in e for e in errors))


if __name__ == "__main__":
    unittest.main()
