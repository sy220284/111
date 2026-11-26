import unittest
from ci_queue_policy import eligible, reconcile

REPO="o/r"
OLD="a"*40
NEW="b"*40

def pr(sha=NEW):
    return {"number":12,"state":"open","head":{"repo":{"full_name":REPO},"ref":"feature","sha":sha},"base":{"ref":"main"}}

def run(id,sha,status="queued"):
    return {"id":id,"head_sha":sha,"head_branch":"feature","head_repository":{"full_name":REPO},
            "event":"pull_request","path":".github/workflows/ci.yml","status":status,
            "pull_requests":[{"number":12}]}

class QueueTests(unittest.TestCase):
    def test_only_idle_superseded_pr_candidate(self):
        old,new=run(1,OLD),run(2,NEW)
        self.assertTrue(eligible(REPO,old,pr(),new,[{"status":"completed"},{"status":"queued"}]))
        self.assertFalse(eligible(REPO,old,pr(),new,[{"status":"in_progress"}]))
        self.assertFalse(eligible(REPO,old,pr(),new,[{"status":"unknown"}]))
        self.assertFalse(eligible(REPO,new,pr(),new,[]))
        self.assertFalse(eligible(REPO,dict(old,event="workflow_dispatch"),pr(),new,[]))
        self.assertFalse(eligible(REPO,dict(old,head_repository={"full_name":"fork/r"}),pr(),new,[]))
        self.assertFalse(eligible(REPO,old,pr(),dict(new,status="completed",conclusion="failure"),[]))
        self.assertFalse(eligible(REPO,old,pr(OLD),new,[]))

    def test_dry_run_and_live_head_recheck(self):
        old,new=run(1,OLD),run(2,NEW)
        calls=[]
        def api(repo,path):
            if path=="pulls/12": return pr()
            if path.startswith("compare/"): return {"status":"ahead"}
            return old if path.endswith("/1") else new
        def pages(repo,path,field):
            return [old,new] if field=="workflow_runs" else [{"status":"completed"}]
        self.assertEqual([1],reconcile(REPO,12,api,pages,lambda *x:calls.append(x),False))
        self.assertEqual([],calls)
        self.assertEqual([1],reconcile(REPO,12,api,pages,lambda *x:calls.append(x),True))
        self.assertEqual([(REPO,1)],calls)
        def changed(repo,path):
            if path=="actions/runs/1": return dict(old,status="in_progress")
            return api(repo,path)
        self.assertEqual([],reconcile(REPO,12,changed,pages,lambda *x:calls.append(x),True))
        self.assertEqual(1,len(calls))

    def test_diverged_history_is_preserved(self):
        old,new=run(1,OLD),run(2,NEW)
        def api(repo,path):
            return {"status":"diverged"} if path.startswith("compare/") else pr()
        self.assertEqual([],reconcile(REPO,12,api,lambda *x:[old,new],lambda *x:self.fail("cancel"),True))

if __name__=="__main__":
    unittest.main()
