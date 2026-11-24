import unittest

from protocol_contract import parse_contracts, parse_notifications
from verify_protocol_methods import protocol_methods, verify_sources


class ProtocolMethodParsingTest(unittest.TestCase):
    def test_distinguishes_official_requests_and_notifications(self):
        source = '''
        ThreadStart => "thread/start" {
            params: v2::ThreadStartParams,
        }
        ThreadStatusChanged => "thread/status/changed" (v2::ThreadStatusChangedNotification),
        AccountUpdated => "account/updated" (v2::AccountUpdatedNotification),
        #[serde(rename = "account/login/completed")]
        AccountLoginCompleted(v2::AccountLoginCompletedNotification),
        '''
        requests, notifications = protocol_methods(source)
        self.assertEqual({"thread/start"}, requests)
        self.assertEqual({"thread/status/changed", "account/updated",
                          "account/login/completed"}, notifications)

    def test_notification_is_not_silently_treated_as_client_request(self):
        source = 'ThreadStatusChanged => "thread/status/changed" (v2::ThreadStatusChangedNotification)'
        requests, notifications = protocol_methods(source)
        self.assertEqual(set(), requests)
        self.assertEqual({"thread/status/changed"}, notifications)


MACROS = '''
client_request_definitions! {
    Start => "thread/start" {
        params: v2::ThreadStartParams,
        response: v2::ThreadStartResponse,
    },
}
server_request_definitions! {
    Prompt => "item/tool/call" {
        params: v2::DynamicToolCallParams,
        response: v2::DynamicToolCallResponse,
    },
}
server_notification_definitions! {
    Started => "thread/started" (v2::ThreadStartedNotification),
}
'''


class DirectionValidationTest(unittest.TestCase):
    def test_parses_both_directions_and_notification(self):
        c = parse_contracts(MACROS)
        self.assertEqual("client_request", c["thread/start"].direction)
        self.assertEqual("host_request", c["item/tool/call"].direction)
        self.assertEqual({"thread/started"}, parse_notifications(MACROS))

    def test_rejects_server_owning_method_sent_as_client_rpc(self):
        errors, _ = verify_sources(MACROS, {
            "ViewModel.kt": 'bridge.request("item/tool/call", JSONObject())'})
        self.assertTrue(any("请求方向错误" in e for e in errors))

    def test_rejects_notification_as_request(self):
        errors, _ = verify_sources(MACROS, {
            "ViewModel.kt": 'vm.refresh("events", "thread/started")'})
        self.assertTrue(any("通知不能作为请求" in e for e in errors))

    def test_session_and_capability_requests_are_direction_checked(self):
        for entry in ("requestForSession", "capabilityRequest"):
            errors, used = verify_sources(MACROS, {
                "ViewModel.kt": f'bridge.{entry}("thread/start", JSONObject())\n'
                                f'bridge.{entry}("item/tool/call", JSONObject())\n'
                                f'bridge.{entry}("thread/started", JSONObject())'})
            self.assertIn("thread/start", used)
            self.assertTrue(any("请求方向错误" in e for e in errors))
            self.assertTrue(any("通知不能作为请求" in e for e in errors))

    def test_valid_request_is_not_rejected(self):
        errors, used = verify_sources(MACROS, {
            "ViewModel.kt": 'bridge.request("thread/start", JSONObject())'})
        self.assertEqual([], errors)
        self.assertEqual({"thread/start"}, used)

    def test_local_paths_are_allowed_but_unknown_rpc_calls_are_rejected(self):
        errors, _ = verify_sources(MACROS, {"Workspace.kt": (
            'val folder = "Download/Codex"\nval archive = "files/projects"\n'
            'bridge.request("typo/start", JSONObject())\n'
            'vm.rpc(writeKey, "other/start", JSONObject())')})
        self.assertEqual([
            "协议未定义：Workspace.kt:3: typo/start",
            "协议未定义：Workspace.kt:4: other/start",
        ], errors)

    def test_invalid_method_is_rejected(self):
        errors, _ = verify_sources(MACROS, {"Screens.kt": 'val a = "thread/typo"'})
        self.assertTrue(any("协议未定义" in e for e in errors))


if __name__ == "__main__":
    unittest.main()
