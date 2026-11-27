import unittest
import re
from pathlib import Path
from release_outcome import verdict, CHAIN
from ci_metrics import summarize

ROOT = Path(__file__).resolve().parents[1] / "workflows"

class ExecutionTests(unittest.TestCase):
    def test_each_runner_has_explicit_timeout(self):
        for path in ROOT.glob("*.yml"):
            source = path.read_text()
            if "\njobs:\n" not in source:
                continue
            jobs = source.split("\njobs:\n", 1)[1]
            blocks = re.split(r"(?m)^  [a-z][a-z0-9-]*:\s*$", jobs)
            for block in blocks:
                if re.search(r"(?m)^    runs-on:", block):
                    with self.subTest(workflow=path.name, job=block[:100]):
                        match = re.search(r"(?m)^    timeout-minutes: (\d+)\s*$", block)
                        self.assertIsNotNone(match)
                        self.assertTrue(0 < int(match.group(1)) <= 180)
    def test_independent_abi_checks(self):
        ci = (ROOT / "ci.yml").read_text()
        runtime = ci.split("\n  runtime-test:\n", 1)[1].split("\n  verify-apk:\n", 1)[0]
        apk = ci.split("\n  verify-apk:\n", 1)[1].split("\n  preflight:\n", 1)[0]
        self.assertIn("needs: [plan, native]", runtime)
        self.assertIn("needs: [plan, build]", apk)
        self.assertIn("runtime-test, verify-apk", ci.split("\n  quality:\n", 1)[1])
    def test_release_never_accepts_partial_chain(self):
        jobs = {k: {"result": "success"} for k in ("preflight", "check-release", *CHAIN)}
        jobs["preflight"]["result"] = "skipped"
        jobs["check-release"]["outputs"] = {"should_release": "true"}
        self.assertIn("均已完成", verdict(jobs, "workflow_run", "refs/heads/main", True))
        for key in CHAIN:
            for status in ("skipped", "failure", "cancelled"):
                jobs[key]["result"] = status
                with self.assertRaises(ValueError):
                    verdict(jobs, "workflow_run", "refs/heads/main", True)
            jobs[key]["result"] = "success"
        jobs["check-release"]["outputs"]["should_release"] = "false"
        for key in CHAIN:
            jobs[key]["result"] = "skipped"
        self.assertIn("无可发布", verdict(jobs, "workflow_run", "refs/heads/main", True))
    def test_metrics_exclude_skipped_and_unfinished(self):
        jobs = [{"name":"build", "status":"completed", "conclusion":"success", "started_at":"2026-01-01T00:01:00Z", "completed_at":"2026-01-01T00:03:00Z"}, {"name":"skip", "status":"completed", "conclusion":"skipped"}, {"name":"quality", "status":"in_progress"}]
        result = summarize(jobs, "2026-01-01T00:00:00Z")
        self.assertEqual(result["runner_minutes"], 2)
        self.assertEqual(result["elapsed_to_last_completed_minutes"], 3)
