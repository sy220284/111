import unittest
from datetime import datetime, timezone, timedelta
from unittest.mock import patch
from ci_reuse import (GROUPS, JOB_NAMES, ARTIFACTS, affected, candidate, make_plan, run_names, artifact_index,
                      validate, check_shape)

class ReuseTests(unittest.TestCase):
    def setUp(self):
        self.source="a"*40
        self.target="b"*40
        self.now=datetime.now(timezone.utc)
        self.run={"id":12,"head_sha":self.source,"head_repository":{"full_name":"o/r"},
                  "status":"completed","conclusion":"success","path":".github/workflows/ci.yml",
                  "event":"pull_request","created_at":self.now.isoformat(), "run_attempt":1,
                  "run_started_at":self.now.isoformat(), "updated_at":self.now.isoformat()}
        self.names={"111 合并验收", "统一变更分类"} | set().union(*JOB_NAMES.values())
        self.artifacts={n:{"id":i+1,"digest":"sha256:"+"1"*64}
                        for i,n in enumerate(sorted(set().union(*ARTIFACTS.values())))}
    def test_exact_tree_and_safe_dependency_changes(self):
        with patch("ci_reuse.ancestor",return_value=True), patch("ci_reuse.tree",return_value="tree"), \
             patch("ci_reuse.run_names",return_value=self.names),patch("ci_reuse.artifact_index",return_value=self.artifacts), \
             patch("ci_reuse.delta",return_value=[]):
            self.assertEqual(set(GROUPS),set(candidate("o/r",self.run,self.target,self.now)))
        with patch("ci_reuse.ancestor",return_value=True),patch("ci_reuse.tree",return_value="tree"), \
             patch("ci_reuse.run_names",return_value=self.names),patch("ci_reuse.artifact_index",return_value=self.artifacts), \
             patch("ci_reuse.delta",return_value=["android-app/app/src/main/Foo.kt"]):
            self.assertEqual({"compat"},set(candidate("o/r",self.run,self.target,self.now)))
    def test_failed_missing_foreign_old_and_unknown_never_reuse(self):
        with patch("ci_reuse.ancestor",return_value=True),patch("ci_reuse.tree",return_value="tree"), \
             patch("ci_reuse.run_names",return_value={"统一变更分类"}),patch("ci_reuse.artifact_index",return_value={}), \
             patch("ci_reuse.delta",return_value=[]):
            self.assertEqual({},candidate("o/r",self.run,self.target,self.now))
            self.assertEqual({},candidate("o/r",dict(self.run,conclusion="failure"),self.target,self.now))
            self.assertIsNone(candidate("o/r",dict(self.run,head_repository={"full_name":"other/r"}),self.target,self.now))
            self.assertIsNone(candidate("o/r",self.run,self.target,self.now+timedelta(days=3)))
        self.assertEqual(set(GROUPS),affected(".github/workflows/ci.yml"))
        self.assertEqual(set(GROUPS),affected("new-unclassified-file"))
        self.assertEqual({"preflight"},affected(".github/scripts/generate-release-notes.py"))
    def test_receipt_revalidation_and_runtime_dependency(self):
        proof={"run_id":12,"source":self.source,"tree":"old","artifacts":{}}
        required={"compile","ui","runtime","package"}
        with patch("ci_reuse.api",return_value={"workflow_runs":[self.run]}), \
             patch("ci_reuse.candidate",return_value={"compile":proof,"ui":proof,"package":proof}), \
             patch("ci_reuse.tree",return_value="new"):
            record=make_plan("o/r",self.target,required)
            self.assertEqual({"runtime","package"},set(record["execute"]))
            self.assertEqual({"compile":proof,"ui":proof},record["reused"])
            with patch("ci_reuse.api",return_value=self.run):
                self.assertEqual({"runtime","package"},validate("o/r",record,self.target,required,now=self.now))
                record["reused"]["ui"]=dict(proof,tree="tampered")
                with self.assertRaises(ValueError): validate("o/r",record,self.target,required,now=self.now)
    def test_manual_force_never_reuses(self):
        with patch("ci_reuse.tree",return_value="tree"),patch("ci_reuse.api") as api:
            r=make_plan("o/r",self.target,{"ui"},True)
            self.assertEqual(["ui"],r["execute"])
            api.assert_not_called()
    def test_missing_groups_and_wrong_source_block(self):
        with patch("ci_reuse.tree",return_value="tree"):
            r=make_plan("o/r",self.target,{"ui"},True)
            with self.assertRaises(ValueError): check_shape(r,self.source,{"ui"})
            with self.assertRaises(ValueError): check_shape(r,self.target,{"ui","static"})

    def test_completed_failure_preserves_only_actual_successful_groups(self):
        names={"统一变更分类"} | JOB_NAMES["compile"] | JOB_NAMES["preflight"]
        with patch("ci_reuse.ancestor",return_value=True),patch("ci_reuse.tree",return_value="tree"), \
             patch("ci_reuse.run_names",return_value=names),patch("ci_reuse.artifact_index",return_value=self.artifacts), \
             patch("ci_reuse.delta",return_value=["android-app/tools/run_ui_motion_checks.py"]):
            result=candidate("o/r",dict(self.run,conclusion="failure"),self.target,self.now)
            self.assertEqual({"compile"},set(result))
            self.assertEqual(1,result["compile"]["attempt"])
            self.assertIsNone(candidate("o/r",dict(self.run,status="in_progress"),self.target,self.now))

    def test_run_attempt_and_artifact_window(self):
        jobs={"total_count":2,"jobs":[{"name":"old","conclusion":"success","run_attempt":1},
                                      {"name":"new","conclusion":"success","run_attempt":2}]}
        with patch("ci_reuse.api",return_value=jobs):
            self.assertEqual({"new"},run_names("o/r",12,2))
        old=self.now-timedelta(hours=1)
        row={"name":"proof","id":1,"expired":False,"size_in_bytes":8,
             "digest":"sha256:"+"1"*64,"created_at":old.isoformat()}
        current=dict(row,id=2,created_at=self.now.isoformat())
        with patch("ci_reuse.api",return_value={"total_count":2,"artifacts":[row,current]}):
            self.assertEqual(2,artifact_index("o/r",12,self.now,self.now)["proof"]["id"])
        with patch("ci_reuse.api",return_value={"total_count":2,"artifacts":[current,dict(current,id=3)]}):
            with self.assertRaises(ValueError): artifact_index("o/r",12,self.now,self.now)
