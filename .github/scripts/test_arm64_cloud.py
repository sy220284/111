import json
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from run_arm64_cloud import DEVICE, expected_tests, result_tests, session_result


def session(sha):
    passed = {"status": {"statusType": "DONE"}, "result": {"resultType": "PASSED"}}
    return {"name": "projects/test/locations/global/sessions/session-test",
            "sessionConfig": {"jobConfigs": [{"labels": {"source_sha": sha},
                "allocationConfig": {"deviceConfigs": [{"requirement": {"deviceId": DEVICE}}]}}]},
            "sessionReport": dict(passed, jobReports=[dict(passed, executionReports=[dict(passed)])])}


class Arm64CloudTests(unittest.TestCase):
    def test_exact_device_source_and_all_success(self):
        sha = "a"*40
        self.assertIsInstance(session_result(session(sha), sha), dict)
        with self.assertRaises(ValueError):
            session_result(session(sha), "b"*40)
        data = session(sha)
        data["sessionConfig"]["jobConfigs"][0]["allocationConfig"]["deviceConfigs"][0]["requirement"]["deviceId"] = "wrong"
        with self.assertRaises(ValueError):
            session_result(data, sha)
        data = session(sha)
        data["sessionReport"]["jobReports"][0]["executionReports"][0]["result"] = {"resultType": "FAILED"}
        with self.assertRaises(ValueError):
            session_result(data, sha)

    def test_exact_junit_set_no_missing_or_skipped_tests(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder)/"junit.xml"
            tests = ET.Element("testsuite")
            for cls, name in sorted(expected_tests()):
                ET.SubElement(tests, "testcase", {"classname": cls, "name": name})
            ET.ElementTree(tests).write(path)
            self.assertEqual(expected_tests(), result_tests(path))
            ET.SubElement(tests[0], "skipped")
            ET.ElementTree(tests).write(path)
            with self.assertRaises(ValueError):
                result_tests(path)
            tests.remove(tests[0])
            ET.ElementTree(tests).write(path)
            with self.assertRaises(ValueError):
                result_tests(path)
