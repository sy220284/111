#!/usr/bin/env python3
import unittest

from audit_ui_feature_coverage import find_rpc_calls


class SourceCallsiteClassificationTest(unittest.TestCase):
    def test_metadata_literals_do_not_count_as_calls(self):
        source = '''
        ProtocolSurface("thread/read", ApiExposure.Direct, "会话", false)
        val label = "thread/read"
        val method = "thread/read"
        '''
        self.assertEqual(set(), find_rpc_calls(source, {"thread/read"}))

    def test_direct_calls_are_detected_across_lines(self):
        source = '''
        bridge.request("thread/read", JSONObject())
        vm.rpc("save", "thread/goal/set",
            JSONObject().put("threadId", id))
        vm.refresh("threads",
            "thread/list", JSONObject())
        '''
        self.assertEqual({"thread/read", "thread/goal/set", "thread/list"},
                         find_rpc_calls(source, {
                             "thread/read", "thread/goal/set", "thread/list"
                         }))

    def test_dynamic_routing_is_not_wrongly_marked_concrete(self):
        source = 'vm.rpc("execute", method, params)'
        self.assertEqual(set(), find_rpc_calls(source, {"thread/delete"}))


    def test_concatenated_keys_and_nested_calls_are_detected(self):
        code = '''
        vm.refresh(prefix + nextPage, "thread/turns/list",
            JSONObject().put("threadId", id))
        vm.rpc(if (active) "interrupt" else "interrupt:" + id,
            "turn/interrupt", JSONObject().put("turnId", id))
        vm.refresh("pluginSearch:" + term + ":0",
            "plugin/search", PluginSearchContract.params(term))
        '''
        expected = {"thread/turns/list", "turn/interrupt", "plugin/search"}
        self.assertEqual(expected, find_rpc_calls(code, expected))

    def test_when_method_dispatch_is_counted_only_in_request(self):
        code = '''
        val description = "thread/unarchive"
        vm.rpc("library", when (kind) {
            "delete" -> "thread/delete"
            "archive" -> "thread/archive"
            else -> "thread/unarchive"
        }, JSONObject().put("threadId", id))
        '''
        expected = {"thread/delete", "thread/archive", "thread/unarchive"}
        self.assertEqual(expected, find_rpc_calls(code, expected))

    def test_local_method_variable_requires_an_actual_request(self):
        code = '''
        val method = if (usage) "account/usage/read" else "account/rateLimits/read"
        val doc = "account/usage/read"
        vm.refresh(selectedTab, method)
        '''
        methods = {"account/usage/read", "account/rateLimits/read"}
        self.assertEqual(methods, find_rpc_calls(code, methods))
        self.assertEqual(set(), find_rpc_calls(code.split("vm.refresh")[0], methods))

    def test_unrelated_method_literals_do_not_prove_execution(self):
        code = '''
        val description = "thread/archive"
        vm.rpc("save", method, params)
        '''
        self.assertEqual(set(), find_rpc_calls(code, {"thread/archive"}))


if __name__ == "__main__":
    unittest.main()
