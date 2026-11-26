#!/usr/bin/env python3
"""Release barrier must not mistake green preflight-only runs for real device checks."""
import sys
import json
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
from verify_release_acceptance import REQUIRED_JOBS, accepted, find_acceptance, accepted_receipt


class ReleaseAcceptanceTests(unittest.TestCase):
    def test_automatic_release_cannot_skip_device_chain_or_report_false_success(self):
        # workflow_run intentionally skips manual preflight. Every downstream
        # required job must override the default transitive success() check.
        import re
        workflow = (Path(__file__).resolve().parents[1] / "workflows" / "release.yml").read_text(encoding="utf-8")
        jobs = dict(re.findall(r"^  ([a-z][a-z-]*):\n(.*?)(?=^  [a-z][a-z-]*:\n|\Z)", workflow, re.M | re.S))
        requirements = {
            "device-ready": ("release", "check-release"),
            "device": ("release", "check-release", "device-ready"),
            "cloud-ready": ("release", "check-release"),
            "cloud-virtual": ("release", "check-release", "cloud-ready"),
            "publish": ("release", "check-release", "device", "cloud-virtual"),
            "cleanup": ("publish", "release-result"),
        }
        for name, upstream in requirements.items():
            with self.subTest(job=name):
                condition = re.search(r"^    if: (.+)$", jobs[name], re.M).group(1)
                self.assertIn("!cancelled()", condition)
                for dependency in upstream:
                    self.assertIn("needs." + dependency + ".result == 'success'", condition)
        terminal = jobs["release-result"]
        self.assertIn("needs: [check-release, release, device-ready, device, cloud-ready, cloud-virtual, publish]", terminal)
        self.assertIn("!cancelled()", terminal)
        self.assertIn("needs.check-release.outputs.should_release == 'true'", terminal)
        for name in ("release", "device-ready", "device", "cloud-ready", "cloud-virtual", "publish"):
            self.assertIn("needs." + name + ".result", terminal)
        self.assertIn('for status in "$SIGNED" "$DEVICE_READY" "$DEVICE" "$CLOUD_READY" "$CLOUD" "$PUBLISHED"', terminal)
        self.assertIn('exit 1', terminal)

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
        self.assertFalse(accepted("ci.yml", [
            {"name": "判断是否等待原生内核", "conclusion": "success"},
            {"name": "build", "conclusion": "skipped"},
            {"name": "ARM64 手机安装包二次核验", "conclusion": "skipped"},
        ]))

    def test_failed_or_cancelled_device_job_blocks_release(self):
        jobs = [
            {"name": name, "conclusion": "success"}
            for name in REQUIRED_JOBS["ci.yml"]
        ]
        jobs[-1]["conclusion"] = "cancelled"
        self.assertFalse(accepted("ci.yml", jobs))

    def test_check_last_valid_run_when_earlier_run_skipped(self):
        sha = "a" * 40
        runs = [
            {"databaseId": 9, "headSha": sha, "conclusion": "success", "event": "push", "headBranch": "main"},
            {"databaseId": 8, "headSha": sha, "conclusion": "success", "event": "push", "headBranch": "main"},
        ]
        mocked = [runs, {"jobs": [{"name": "判断是否等待原生内核",
                                   "conclusion": "success"}]},
                  {"jobs": [{"name": name, "conclusion": "success"}
                            for name in REQUIRED_JOBS["ci.yml"]]}]
        with patch("verify_release_acceptance.gh_json", side_effect=mocked) as client:
            self.assertEqual(find_acceptance("org/repo", sha, "ci.yml"), 8)
            self.assertEqual(client.call_count, 3)

    def test_pr_branch_checks_same_sha_and_all_required_jobs(self):
        sha = "a" * 40
        mocked = [[{"databaseId": 10, "headSha": sha, "conclusion": "success", "event": "push", "headBranch": "main"}],
                  {"jobs": [{"name": name, "conclusion": "success"}
                            for name in REQUIRED_JOBS["ci.yml"]]}]
        with patch("verify_release_acceptance.gh_json", side_effect=mocked) as client:
            self.assertEqual(find_acceptance("org/repo", sha, "ci.yml", "fix/pr"), 10)
            self.assertIn("fix/pr", client.call_args_list[0].args[0])
            self.assertIn(sha, client.call_args_list[0].args[0])

    def test_partial_main_must_prove_all_reused_groups(self):
        from ci_reuse import FULL_GROUPS
        sha="a"*40
        names=[{"name":"111 合并验收","conclusion":"success"}]
        receipt={"required":sorted(FULL_GROUPS)}
        def download(args,check):
            folder=Path(args[args.index("--dir")+1])
            (folder/"ci-acceptance-receipt.json").write_text(json.dumps(receipt))
        with patch("verify_release_acceptance.gh_json",return_value={"artifacts":[{"name":"ci-acceptance-receipt","expired":False}]}), \
             patch("verify_release_acceptance.subprocess.run",side_effect=download), \
             patch("verify_release_acceptance.validate",return_value=set()) as verify:
            self.assertTrue(accepted_receipt("o/r",12,sha,names,"main"))
            verify.assert_called_once()
            receipt["required"]=[]
            self.assertFalse(accepted_receipt("o/r",12,sha,names,"main"))

    def test_other_sha_is_never_validated(self):
        sha = "a" * 40
        with patch("verify_release_acceptance.gh_json") as client:
            client.return_value = [
                {"databaseId": 9, "headSha": "b" * 40, "conclusion": "success"}
            ]
            self.assertIsNone(find_acceptance("org/repo", sha, "ci.yml"))


if __name__ == "__main__":
    unittest.main()
