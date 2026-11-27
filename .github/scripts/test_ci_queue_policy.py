import unittest
from pathlib import Path
from unittest.mock import patch
from ci_queue_policy import eligible, reconcile, pages, active_runs, cutover

REPO = "o/r"
OLD = "a"*40
NEW = "b"*40
def pr(sha=NEW, state="open"):
    return {"number":12,"state":state,"head":{"repo":{"full_name":REPO},"ref":"feature","sha":sha},"base":{"ref":"main"}}
def run(id=1, sha=OLD, status="queued"):
    return {"id":id,"head_sha":sha,"head_branch":"feature","head_repository":{"full_name":REPO},
            "event":"pull_request","path":".github/workflows/ci.yml","status":status,"pull_requests":[{"number":12}]}

class QueueTests(unittest.TestCase):
    @patch("ci_queue_policy.gh")
    def test_api_pagination_with_existing_query(self, gh):
        import json
        gh.side_effect = [json.dumps({"workflow_runs": list(range(100))}),
                          json.dumps({"workflow_runs": [100]})]
        self.assertEqual(list(range(101)), list(pages(REPO, "actions/runs?status=queued", "workflow_runs")))
        self.assertEqual([
            ("api", "repos/o/r/actions/runs?status=queued&per_page=100&page=1"),
            ("api", "repos/o/r/actions/runs?status=queued&per_page=100&page=2"),
        ], [call.args for call in gh.call_args_list])

    @patch('ci_queue_policy.pages')
    def test_active_runs_encodes_branch_and_all_active_states(self, pages):
        pages.return_value = []
        self.assertEqual([], list(active_runs(REPO, 'feature/topic')))
        self.assertEqual([f'actions/runs?status={status}&branch=feature%2Ftopic' for status in ('queued', 'in_progress', 'waiting', 'requested', 'pending')], [call.args[1] for call in pages.call_args_list])

    def test_latest_pr_supersedes_old_head_and_producers_keep_shared_locks(self):
        root = Path(__file__).resolve().parents[2]
        workflow = (root / ".github/workflows/ci.yml").read_text()
        concurrency = workflow.split("\nconcurrency:\n", 1)[1].split("\njobs:\n", 1)[0]
        self.assertIn("github.event.pull_request.number || github.ref", concurrency)
        self.assertIn("github.event_name == 'pull_request' && 'latest' || github.sha", concurrency)
        self.assertIn("cancel-in-progress: ${{ github.event_name == 'pull_request' }}", concurrency)
        queue = (root / ".github/workflows/reconcile-ci-queue.yml").read_text()
        self.assertNotIn("workflow_run:", queue)
        self.assertIn("types: [synchronize, closed]", queue)

    def test_only_exact_obsolete_or_closed_pr(self):
        for status in ("queued","pending","in_progress","waiting","requested"):
            self.assertTrue(eligible(REPO,run(status=status),pr()))
        self.assertTrue(eligible(REPO,run(sha=NEW),pr(state="closed")))
        for item in (run(sha=NEW),run(status="completed"),dict(run(),event="push"),
                     dict(run(),head_repository={"full_name":"fork/r"}),
                     dict(run(),pull_requests=[{"number":13}]),
                     dict(run(),pull_requests=[{"number":12},{"number":13}])):
            self.assertFalse(eligible(REPO,item,pr()))

    def test_reconcile_rechecks_live_head_and_run_and_deduplicates(self):
        calls=[]
        def request(repo,path):
            return pr() if path=="pulls/12" else run()
        def active(repo,branch):
            return [run(),run(),run(id=2,sha=NEW)]
        self.assertEqual([1],reconcile(REPO,12,request,active,lambda *x:calls.append(x)))
        self.assertEqual([],calls)
        self.assertEqual([1],reconcile(REPO,12,request,active,lambda *x:calls.append(x),True))
        self.assertEqual([(REPO,1)],calls)
        def complete(repo,path):
            return pr() if path=="pulls/12" else run(status="completed")
        self.assertEqual([],reconcile(REPO,12,complete,active,lambda *x:self.fail("cancel"),True))
        def current(repo,path):
            return pr(OLD) if path=="pulls/12" else run()
        self.assertEqual([],reconcile(REPO,12,current,active,lambda *x:self.fail("cancel"),True))

    def test_manual_scan_and_closed_pr(self):
        def request(repo,path):
            return pr(state="closed") if path=="pulls/12" else run(sha=NEW)
        self.assertEqual([1],reconcile(REPO,None,request,lambda *x:[run(sha=NEW)]))

    def test_explicit_main_cutover_cancels_old_ci_only(self):
        control = dict(run(id=99, sha=NEW), head_branch="main", event="push",
                       path=".github/workflows/reconcile-ci-queue.yml",
                       created_at="2026-11-27T11:00:00Z")
        old = dict(run(), created_at="2026-11-27T10:00:00Z", pull_requests=[])
        rows = [
            old, old, dict(old, id=2, event="push", head_branch="main"),
            dict(old, id=3, event="workflow_dispatch"),
            dict(old, id=4, head_sha=NEW),
            dict(old, id=5, created_at="2026-11-27T11:00:01Z"),
            dict(old, id=6, path=".github/workflows/release.yml"),
            dict(old, id=7, head_repository={"full_name":"fork/r"}),
            dict(old, id=8, status="completed"),
        ]
        live = {r["id"]:r for r in rows} | {99:control}
        def request(repo, path):
            if path == "git/ref/heads/main":
                return {"object":{"sha":NEW}}
            return live[int(path.rsplit("/",1)[1])]
        calls=[]
        self.assertEqual([1,2,3], cutover(REPO,NEW,99,request,lambda *x:rows,lambda *x:calls.append(x)))
        self.assertEqual([], calls)
        self.assertEqual([1,2,3], cutover(REPO,NEW,99,request,lambda *x:rows,lambda *x:calls.append(x),True))
        self.assertEqual([(REPO,1),(REPO,2),(REPO,3)],calls)
        live[99] = dict(control, head_sha=OLD)
        with self.assertRaises(ValueError):
            cutover(REPO,NEW,99,request,lambda *x:rows)
        with self.assertRaises(ValueError):
            cutover(REPO,OLD,99,request,lambda *x:rows)

    def test_cutover_is_explicit_main_only_and_forces_full_acceptance(self):
        root = Path(__file__).resolve().parents[2]
        ci = (root / ".github/workflows/ci.yml").read_text()
        queue = (root / ".github/workflows/reconcile-ci-queue.yml").read_text()
        trigger = "github.event_name == 'push' && github.ref == 'refs/heads/main' && startsWith(github.event.head_commit.message, '主线强制切换新版')"
        self.assertIn("CI_FORCE: ${{ github.event_name == 'workflow_dispatch' || (" + trigger + ") }}",ci)
        self.assertIn(trigger,queue)
        self.assertIn('--cutover-sha "$CUTOVER_SHA" --cutover-run "$GITHUB_RUN_ID"',queue)

if __name__ == "__main__":
    unittest.main()
