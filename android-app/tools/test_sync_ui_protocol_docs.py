#!/usr/bin/env python3
import unittest

from sync_ui_protocol_docs import render_document


class ProtocolDocumentSyncTests(unittest.TestCase):
    def setUp(self):
        self.policy = {"methods": {
            "thread/start": {"surface": "直接操作", "owner": "会话", "flag": "常规/内部"},
            "item/tool/call": {"surface": "后台内部", "owner": "Host", "flag": "常规/内部"},
            "server/diagnostics": {"surface": "高级按需", "owner": "诊断", "flag": "实验"}
        }, "implementationEquivalents": {}}

    def test_counts_and_owners_come_from_policy(self):
        result = render_document(self.policy)
        self.assertIn("总数 3：直接 1、上下文 0、高级 1、后台 1、平台不适用 0", result)
        self.assertIn("item/tool/call", result)
        self.assertIn("| 后台内部 | Host | 否 |", result)
        self.assertEqual(result, render_document(self.policy))

    def test_new_methods_appear_automatically(self):
        self.policy["methods"]["thread/read"] = {
            "surface": "上下文操作", "owner": "会话", "flag": "常规/内部"}
        self.assertIn("thread/read", render_document(self.policy))

    def test_unknown_exposure_and_flags_fail_closed(self):
        self.policy["methods"]["thread/start"]["surface"] = "未知类别"
        with self.assertRaises(ValueError):
            render_document(self.policy)
        self.policy["methods"]["thread/start"]["surface"] = "直接操作"
        self.policy["methods"]["thread/start"]["flag"] = "未知标记"
        with self.assertRaises(ValueError):
            render_document(self.policy)


if __name__ == "__main__":
    unittest.main()
