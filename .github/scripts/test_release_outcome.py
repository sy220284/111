import unittest
from release_outcome import verdict, CHAIN, REASONS

def jobs(decision="publish"):
    return {"preflight":{"result":"skipped"}, "check-release":{"result":"success","outputs":{"decision":decision}},
            **{k:{"result":"success" if decision=="publish" else "skipped"} for k in CHAIN}}

class ReleaseOutcomeTests(unittest.TestCase):
    def test_published_and_explicit_no_release_states(self):
        self.assertIn("已完成",verdict(jobs(),"workflow_run","refs/heads/main",True))
        for key, message in REASONS.items():
            self.assertEqual(message,verdict(jobs(key),"workflow_run","refs/heads/main",True))
    def test_every_chain_job_is_required(self):
        for key in CHAIN:
            j=jobs();j[key]["result"]="skipped"
            with self.assertRaises(ValueError):
                verdict(j,"workflow_run","refs/heads/main",True)
    def test_unknown_decision_is_error(self):
        with self.assertRaises(ValueError):
            verdict(jobs("unknown"),"workflow_run","refs/heads/main",True)

    def test_failed_main_ci_cannot_report_successful_release(self):
        j = {"preflight": {"result": "skipped"}, "check-release": {"result": "skipped"},
             **{k: {"result": "skipped"} for k in CHAIN}}
        for conclusion in ("failure", "timed_out", "action_required", "startup_failure"):
            with self.assertRaisesRegex(ValueError, "上游主线验收未通过"):
                verdict(j, "workflow_run", "refs/heads/main", False, conclusion)
        self.assertIn("已取消", verdict(j, "workflow_run", "refs/heads/main", False, "cancelled"))
        self.assertEqual("当前触发条件无需发布", verdict(j, "workflow_run", "refs/heads/main", False))
