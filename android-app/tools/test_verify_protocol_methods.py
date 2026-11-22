import unittest

from verify_protocol_methods import (
    protocol_methods, protocol_directions, host_policy_methods
)


class ProtocolMethodParsingTest(unittest.TestCase):
    def test_distinguishes_official_requests_and_notifications(self):
        source = '''
        ThreadStart => "thread/start" {
            params: v2::ThreadStartParams,
        }
        ThreadStatusChanged => "thread/status/changed" (v2::ThreadStatusChangedNotification),
        AccountUpdated => "account/updated" (v2::AccountUpdatedNotification),
        #[serde(rename = "account/login/completed")]
        #[ts(rename = "account/login/completed")]
        AccountLoginCompleted(v2::AccountLoginCompletedNotification),
        '''
        requests, notifications = protocol_methods(source)
        self.assertEqual({"thread/start"}, requests)
        self.assertEqual({
            "thread/status/changed", "account/updated", "account/login/completed"
        }, notifications)
        self.assertNotIn("thread/status/changeTypo", requests | notifications)

    def test_pinned_protocol_macros_separate_client_and_host_requests(self):
        source = '''
        client_request_definitions! {
          Init => "initialize" { response: Foo, },
          Start => "thread/start" { response: Foo, },
          Read => "thread/read" { response: Foo, },
        }
        server_request_definitions! {
          Approval => "item/commandExecution/requestApproval" { response: Bar, },
          Input => "item/tool/requestUserInput" { response: Bar, },
        }
        server_notification_definitions! {
          Status => "thread/status/changed" (Status),
        }
        '''
        client, host = protocol_directions(source)
        self.assertEqual({"thread/start", "thread/read"}, client)
        self.assertEqual({
            "item/commandExecution/requestApproval", "item/tool/requestUserInput"
        }, host)
        self.assertFalse(client & host)

    def test_fails_when_upstream_macro_structure_disappears(self):
        with self.assertRaisesRegex(ValueError, "宏结构不完整"):
            protocol_directions('Start => "thread/start" { }')

    def test_detects_missing_and_wrong_host_only_methods(self):
        kotlin = '''
        private val HOST_ONLY = setOf(
            "item/commandExecution/requestApproval", "item/tool/requestUserInput"
        )
        '''
        self.assertEqual({
            "item/commandExecution/requestApproval", "item/tool/requestUserInput"
        }, host_policy_methods(kotlin))
        with self.assertRaisesRegex(ValueError, "缺少"):
            host_policy_methods("internal object FutureContract { }")

    def test_notification_is_not_silently_treated_as_client_request(self):
        source = 'ThreadStatusChanged => "thread/status/changed" (v2::ThreadStatusChangedNotification)'
        requests, notifications = protocol_methods(source)
        self.assertEqual(set(), requests)
        self.assertEqual({"thread/status/changed"}, notifications)


if __name__ == "__main__":
    unittest.main()
