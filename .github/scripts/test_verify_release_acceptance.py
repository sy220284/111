#!/usr/bin/env python3
"""Release barrier must not mistake green preflight-only runs for real device checks."""
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
from verify_release_acceptance import REQUIRED_JOBS, accepted, find_acceptance


class ReleaseAcceptanceTests(unittest.TestCase):
    def test_all_real_android_jobs_are_required(self):
        for workflow, names in REQUIRED_JOBS.items():
            good = [{"name": name, "conclusion": "success"} for name in names]
            self.assertTrue(accepted(workflow, good))
            for name in names:
                with self.subTest(workflow=workflow, missing=name):
                    self.assertFalse(accepted(workflow, [
                        j for j in good if j["name"] != name
                    ]))

    def test_green_push_preflight_does_not_count_as_apk(self):
        self.assertFalse(accepted("build-android.yml", [
            {"name": "判断是否等待原生内核", "conclusion": "success"},
            {"name": "build", "conclusion": "skipped"},
            {"name": "ARM64 手机安装包二次核验", "conclusion": "skipped"},
        ]))

    def test_failed_or_cancelled_device_job_blocks_release(self):
        jobs = [
            {"name": name, "conclusion": "success"}
            for name in REQUIRED_JOBS["check-android-ui.yml"]
        ]
        jobs[-1]["conclusion"] = "cancelled"
        self.assertFalse(accepted("check-android-ui.yml", jobs))

    def test_check_last_valid_run_when_earlier_run_skipped(self):
        sha = "a" * 40
        runs = [
            {"databaseId": 9, "headSha": sha, "conclusion": "success"},
            {"databaseId": 8, "headSha": sha, "conclusion": "success"},
        ]
        mocked = [runs, {"jobs": [{"name": "判断是否等待原生内核",
                                   "conclusion": "success"}]},
                  {"jobs": [{"name": name, "conclusion": "success"}
                            for name in REQUIRED_JOBS["build-android.yml"]]}]
        with patch("verify_release_acceptance.gh_json", side_effect=mocked) as client:
            self.assertEqual(find_acceptance("org/repo", sha, "build-android.yml"), 8)
            self.assertEqual(client.call_count, 3)

    def test_pr_branch_checks_same_sha_and_all_required_jobs(self):
        sha = "a" * 40
        mocked = [[{"databaseId": 10, "headSha": sha, "conclusion": "success"}],
                  {"jobs": [{"name": name, "conclusion": "success"}
                            for name in REQUIRED_JOBS["check-android-ui.yml"]]}]
        with patch("verify_release_acceptance.gh_json", side_effect=mocked) as client:
            self.assertEqual(find_acceptance("org/repo", sha, "check-android-ui.yml", "fix/pr"), 10)
            self.assertIn("fix/pr", client.call_args_list[0].args[0])
            self.assertIn(sha, client.call_args_list[0].args[0])

    def test_other_sha_is_never_validated(self):
        sha = "a" * 40
        with patch("verify_release_acceptance.gh_json") as client:
            client.return_value = [
                {"databaseId": 9, "headSha": "b" * 40, "conclusion": "success"}
            ]
            self.assertIsNone(find_acceptance("org/repo", sha, "build-android.yml"))


if __name__ == "__main__":
    unittest.main()
