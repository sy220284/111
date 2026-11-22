import unittest

from verify_protocol_methods import protocol_methods


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

    def test_notification_is_not_silently_treated_as_client_request(self):
        source = 'ThreadStatusChanged => "thread/status/changed" (v2::ThreadStatusChangedNotification)'
        requests, notifications = protocol_methods(source)
        self.assertEqual(set(), requests)
        self.assertEqual({"thread/status/changed"}, notifications)


if __name__ == "__main__":
    unittest.main()
