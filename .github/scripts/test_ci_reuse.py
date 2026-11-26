import unittest
from datetime import datetime, timezone, timedelta
from unittest.mock import patch
from ci_reuse import (GROUPS, JOB_NAMES, ARTIFACTS, affected, candidate, make_plan,
                      validate, check_shape)

class ReuseTests(unittest.TestCase):
    def setUp(self):
        self.source="a"*40
        self.target="b"*40
        self.now=datetime.now(timezone.utc)
        self.run={"id":12,"head_sha":self.source,"head_repository":{"full_name":"o/r"},
                  "status":"completed","conclusion":"success","path":".github/workflows/ci.yml",
                  "event":"pull_request","created_at":self.now.isoformat()}
        self.names={"111 合并验收"} | set().union(*JOB_NAMES.values())
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
             patch("ci_reuse.run_names",return_value={"111 合并验收"}),patch("ci_reuse.artifact_index",return_value={}), \
             patch("ci_reuse.delta",return_value=[]):
            self.assertEqual({},candidate("o/r",self.run,self.target,self.now))
            self.assertIsNone(candidate("o/r",dict(self.run,conclusion="failure"),self.target,self.now))
            self.assertIsNone(candidate("o/r",dict(self.run,head_repository={"full_name":"other/r"}),self.target,self.now))
            self.assertIsNone(candidate("o/r",self.run,self.target,self.now+timedelta(days=3)))
        self.assertEqual(set(GROUPS),affected(".github/workflows/ci.yml"))
        self.assertEqual(set(GROUPS),affected("new-unclassified-file"))
        self.assertEqual({"preflight"},affected(".github/scripts/generate-release-notes.py"))
    def test_receipt_revalidation_and_runtime_dependency(self):
        proof={"run_id":12,"source":self.source,"tree":"old","artifacts":{}}
        required={"ui","runtime","package"}
        with patch("ci_reuse.api",return_value={"workflow_runs":[self.run]}), \
             patch("ci_reuse.candidate",return_value={"ui":proof,"package":proof}), \
             patch("ci_reuse.tree",return_value="new"):
            record=make_plan("o/r",self.target,required)
            self.assertEqual({"runtime","package"},set(record["execute"]))
            self.assertEqual({"ui":proof},record["reused"])
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
