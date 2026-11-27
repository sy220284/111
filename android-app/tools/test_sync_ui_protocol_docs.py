#!/usr/bin/env python3
import unittest

from sync_ui_protocol_docs import render_document, render_kotlin, render_ledger


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
        self.assertIn("按目标提交的实际 CI 设备矩阵", result)
        self.assertIn("屏宽、系统大字体、TalkBack 及减弱动画验收", result)
        self.assertIn("历史矩阵不替代当前门禁", result)
        self.assertNotIn("高分辨率三档", result)
        self.assertIn("| 后台内部 | Host | 否 |", result)
        self.assertEqual(result, render_document(self.policy))

    def test_kotlin_matrix_is_generated_from_the_same_policy(self):
        kotlin = render_kotlin(self.policy)
        self.assertIn('ProtocolSurface("thread/start", ApiExposure.Direct, "会话", false)', kotlin)
        self.assertIn('ProtocolSurface("item/tool/call", ApiExposure.Internal, "Host", false)', kotlin)
        self.assertEqual(3, kotlin.count("        ProtocolSurface("))

    def test_new_methods_appear_automatically(self):
        self.policy["methods"]["thread/read"] = {
            "surface": "上下文操作", "owner": "会话", "flag": "常规/内部"}
        self.assertIn("thread/read", render_document(self.policy))

    def test_authoritative_ledger_counts_update_without_touching_history(self):
        rows = ["客户端→Codex 服务端请求", "Codex 服务端→Android Host 请求", "协议方法合计",
                "直接操作", "上下文操作", "高级按需", "后台内部", "平台不适用"]
        labels = ["用户可直接操作", "对象操作", "高级按需", "智能体/内核后台", "平台不适用"]
        ledger = "\n".join([f"| {row} | 99 | 原说明 |" for row in rows] +
                           [f"- **{label}（99）**：原说明。" for label in labels] +
                           ["握手不在上述 99 项带斜杠方法中", "历史审计：99 项"]) + "\n"
        clients = {"thread/start", "server/diagnostics"}
        hosts = {"item/tool/call"}
        synced = render_ledger(ledger, self.policy, clients, hosts)
        self.assertIn("| 客户端→Codex 服务端请求 | 2 | 原说明 |", synced)
        self.assertIn("| Codex 服务端→Android Host 请求 | 1 | 原说明 |", synced)
        self.assertIn("| 协议方法合计 | 3 | 原说明 |", synced)
        self.assertIn("- **用户可直接操作（1）**", synced)
        self.assertIn("- **对象操作（0）**", synced)
        self.assertIn("历史审计：99 项", synced)
        self.assertEqual(synced, render_ledger(synced, self.policy, clients, hosts))
        with self.assertRaises(ValueError):
            render_ledger(ledger, self.policy, clients, set())
        with self.assertRaises(ValueError):
            render_ledger(ledger.replace("| 直接操作 |", "| 旧名称 |"), self.policy, clients, hosts)
        with self.assertRaises(ValueError):
            render_ledger(ledger + "| 直接操作 | 99 | 重复 |\n", self.policy, clients, hosts)

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
