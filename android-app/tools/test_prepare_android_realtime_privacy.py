import unittest
from prepare_android_realtime_privacy import REPLACEMENTS, adapt

class RealtimePrivacyTests(unittest.TestCase):
    def test_removes_payload_and_keeps_metadata(self):
        source = "\n".join(old for old, _, n in REPLACEMENTS for _ in range(n))
        result = adapt(source)
        for old, new, count in REPLACEMENTS:
            self.assertNotIn(old, result)
            self.assertEqual(result.count(new), count)
        self.assertEqual(adapt(result), result)

    def test_upstream_changes_fail_closed(self):
        with self.assertRaises(ValueError):
            adapt("unknown log contract")
