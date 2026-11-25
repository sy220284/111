import unittest
from verify_device_evidence import verify, REQUIRED

class DeviceEvidenceTests(unittest.TestCase):
    def test_complete(self):
        self.assertTrue(verify({"version":"v", "apk_sha256":"x", "checks":dict.fromkeys(REQUIRED,"passed")}, "v", "x"))
    def test_missing_skipped_wrong_hash(self):
        for checks, digest in [({}, "x"), (dict.fromkeys(REQUIRED,"skipped"),"x"), (dict.fromkeys(REQUIRED,"passed"),"other")]:
            with self.assertRaises(ValueError):
                verify({"version":"v","apk_sha256":digest,"checks":checks}, "v", "x")
