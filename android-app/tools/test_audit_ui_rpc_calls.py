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


if __name__ == "__main__":
    unittest.main()
