#!/usr/bin/env python3
import unittest
from pathlib import Path

from protocol_contract import parse_contracts, parse_notifications, request_method_names

UPSTREAM = (Path(__file__).resolve().parents[2]
            / "upstream/codex/codex-rs/app-server-protocol/src/protocol/common.rs")


class PinnedProtocolContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = UPSTREAM.read_text(encoding="utf-8")
        cls.methods = parse_contracts(cls.source)

    def test_all_196_methods_have_a_single_direction(self):
        client, host = request_method_names(self.source)
        self.assertEqual(188, len(client))
        self.assertEqual(8, len(host))
        self.assertEqual(196, len(self.methods))
        self.assertFalse(client & host)
        self.assertIn("modelProvider/model/verify", client)

    def test_host_methods_match_supported_and_gated_capabilities(self):
        _client, host = request_method_names(self.source)
        self.assertEqual({
            "item/commandExecution/requestApproval",
            "item/fileChange/requestApproval",
            "item/tool/requestUserInput",
            "mcpServer/elicitation/request",
            "item/permissions/requestApproval",
            "item/tool/call",
            "account/chatgptAuthTokens/refresh",
            "attestation/generate",
        }, host)

    def test_method_level_experimental_tags_are_preserved(self):
        self.assertTrue(self.methods["project/create"].experimental)
        self.assertTrue(self.methods["thread/increment_elicitation"].experimental)
        self.assertFalse(self.methods["command/exec/resize"].experimental)
        self.assertFalse(self.methods["thread/start"].experimental)

    def test_request_and_response_types_present(self):
        self.assertEqual("v2::ThreadStartParams",
                         self.methods["thread/start"].params)
        self.assertEqual("v2::ThreadStartResponse",
                         self.methods["thread/start"].response)
        self.assertEqual("v2::DynamicToolCallResponse",
                         self.methods["item/tool/call"].response)
        for method in self.methods.values():
            self.assertTrue(method.params, method.name)
            self.assertTrue(method.response, method.name)

    def test_notifications_do_not_enter_request_catalog(self):
        notifications = parse_notifications(self.source)
        self.assertGreaterEqual(len(notifications), 40)
        self.assertIn("account/login/completed", notifications)
        self.assertIn("turn/completed", notifications)
        self.assertNotIn("thread/start", notifications)


if __name__ == "__main__":
    unittest.main()
