import unittest
from datetime import datetime, timezone, timedelta
from unittest.mock import patch
from ci_reuse import (GROUPS, JOB_NAMES, ARTIFACTS, affected, candidate, make_plan, run_names, artifact_index,
                      validate, check_shape, actual_jobs)

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
        self.job_records={n:{"id":i+1,"started":self.now.isoformat(),"finished":self.now.isoformat()} for i,n in enumerate(sorted(self.names))}
        self.artifacts={n:{"id":i+1,"digest":"sha256:"+"1"*64}
                        for i,n in enumerate(sorted(set().union(*ARTIFACTS.values())))}
    def test_exact_tree_and_safe_dependency_changes(self):
        with patch("ci_reuse.ancestor",return_value=True), patch("ci_reuse.tree",return_value="tree"), \
             patch("ci_reuse.run_names",return_value=(self.job_records,self.now)),patch("ci_reuse.artifact_index",return_value=self.artifacts), \
             patch("ci_reuse.delta",return_value=[]):
            self.assertEqual(set(GROUPS),set(candidate("o/r",self.run,self.target,self.now)))
        with patch("ci_reuse.ancestor",return_value=True),patch("ci_reuse.tree",return_value="tree"), \
             patch("ci_reuse.run_names",return_value=(self.job_records,self.now)),patch("ci_reuse.artifact_index",return_value=self.artifacts), \
             patch("ci_reuse.delta",return_value=["android-app/app/src/main/Foo.kt"]):
            self.assertEqual({"compat"},set(candidate("o/r",self.run,self.target,self.now)))
    def test_failed_missing_foreign_old_and_unknown_never_reuse(self):
        with patch("ci_reuse.ancestor",return_value=True),patch("ci_reuse.tree",return_value="tree"), \
             patch("ci_reuse.run_names",return_value=({"统一变更分类":self.job_records["统一变更分类"]},self.now)),patch("ci_reuse.artifact_index",return_value={}), \
             patch("ci_reuse.delta",return_value=[]):
            self.assertEqual({},candidate("o/r",self.run,self.target,self.now))
            self.assertEqual({},candidate("o/r",dict(self.run,conclusion="failure"),self.target,self.now))
            self.assertIsNone(candidate("o/r",dict(self.run,head_repository={"full_name":"other/r"}),self.target,self.now))
            self.assertIsNone(candidate("o/r",self.run,self.target,self.now+timedelta(days=8)))
        self.assertEqual(set(GROUPS),affected(".github/workflows/ci.yml"))
        self.assertEqual(set(GROUPS),affected("new-unclassified-file"))
        self.assertEqual(set(),affected("tools/dev/ci_host.py"))
        self.assertEqual(set(),affected(".github/workflows/dev-toolchain.yml"))
        self.assertEqual({"preflight"},affected(".github/scripts/generate-release-notes.py"))
    def test_receipt_revalidation_and_runtime_dependency(self):
        proof={"run_id":12,"source":self.source,"tree":"old","artifacts":{}}
        required={"compile","ui","runtime","package"}
        with patch("ci_reuse.api",return_value={"workflow_runs":[self.run]}), \
             patch("ci_reuse.candidate",return_value={"compile":proof,"ui":proof,"package":proof}), \
             patch("ci_reuse.tree",return_value="new"):
            record=make_plan("o/r",self.target,required)
            self.assertEqual({"runtime"},set(record["execute"]))
            self.assertEqual({"compile":proof,"ui":proof,"package":proof},record["reused"])
            self.assertEqual({"native","runtime-x86","runtime-test"},actual_jobs(record["execute"]))
            with patch("ci_reuse.api",return_value=self.run):
                self.assertEqual({"runtime"},validate("o/r",record,self.target,required,now=self.now))
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
             patch("ci_reuse.run_names",return_value=({n:self.job_records[n] for n in names},self.now)),patch("ci_reuse.artifact_index",return_value=self.artifacts), \
             patch("ci_reuse.delta",return_value=["android-app/tools/run_ui_motion_checks.py"]):
            result=candidate("o/r",dict(self.run,conclusion="failure"),self.target,self.now)
            self.assertEqual({"compile"},set(result))
            self.assertEqual(1,result["compile"]["attempt"])
            active=candidate("o/r",dict(self.run,status="in_progress",conclusion=None),self.target,self.now)
            self.assertEqual(result,active)
            self.assertEqual(set(JOB_NAMES["compile"]),set(active["compile"]["jobs"]))

    def test_run_attempt_and_artifact_window(self):
        jobs={"total_count":4,"jobs":[
            {"name":"old","conclusion":"success","run_attempt":1,"head_sha":self.source},
            {"name":"new","conclusion":"success","run_attempt":2,"head_sha":self.source},
            {"name":"wrong-source","conclusion":"success","run_attempt":2,"head_sha":self.target},
            {"name":"统一变更分类","conclusion":"success","run_attempt":2,"head_sha":self.source,
             "started_at":self.now.isoformat()}]}
        for i,job in enumerate(jobs["jobs"]):
            job.update(id=i+1,status="completed",started_at=self.now.isoformat(),completed_at=self.now.isoformat())
        with patch("ci_reuse.api",return_value=jobs):
            records,started=run_names("o/r",12,2,self.source)
            self.assertEqual({"new","统一变更分类"},set(records))
            self.assertEqual(self.now,started)
            self.assertEqual(({},None),run_names("o/r",12,2,self.target))
        old=self.now-timedelta(hours=1)
        row={"name":"proof","id":1,"expired":False,"size_in_bytes":8,
             "digest":"sha256:"+"1"*64,"created_at":old.isoformat()}
        current=dict(row,id=2,created_at=self.now.isoformat())
        with patch("ci_reuse.api",return_value={"total_count":2,"artifacts":[row,current]}):
            self.assertEqual(2,artifact_index("o/r",12,self.now,self.now)["proof"]["id"])
        with patch("ci_reuse.api",return_value={"total_count":2,"artifacts":[row,current]}) as api:
            snapshot={}
            self.assertEqual(1,artifact_index("o/r",12,old,old,snapshot)["proof"]["id"])
            self.assertEqual(2,artifact_index("o/r",12,self.now,self.now,snapshot)["proof"]["id"])
            api.assert_called_once()
        with patch("ci_reuse.api",return_value={"total_count":2,"artifacts":[current,dict(current,id=3)]}):
            with self.assertRaises(ValueError): artifact_index("o/r",12,self.now,self.now)

    def test_running_job_and_late_artifact_cannot_supply_receipt(self):
        names={"统一变更分类"} | JOB_NAMES["compile"]
        records={n:self.job_records[n] for n in names}
        run=dict(self.run,status="queued",conclusion=None)
        with patch("ci_reuse.ancestor",return_value=True),patch("ci_reuse.tree",return_value="tree"), \
             patch("ci_reuse.run_names",return_value=(records,self.now)),patch("ci_reuse.delta",return_value=[]), \
             patch("ci_reuse.artifact_index",return_value={}) as index:
            self.assertEqual({},candidate("o/r",run,self.target,self.now))
            index.assert_called_once_with("o/r",12,self.now,self.now,{})
        with patch("ci_reuse.ancestor",return_value=True),patch("ci_reuse.delta",return_value=[]), \
             patch("ci_reuse.run_names",return_value=({"统一变更分类":records["统一变更分类"]},self.now)):
            self.assertEqual({},candidate("o/r",run,self.target,self.now))

    def test_independent_runtime_changes_do_not_invalidate_arm_package(self):
        self.assertEqual({"runtime", "preflight"}, affected(".github/scripts/run-android-runtime-tests.sh"))
        self.assertEqual({"native", "runtime-arm64", "build", "verify-apk"}, actual_jobs({"package"}))
        self.assertEqual({"native", "runtime-x86", "runtime-test"}, actual_jobs({"runtime"}))
        self.assertNotIn("native", actual_jobs({"compile", "ui"}))
        self.assertEqual({"ui", "runtime", "preflight"}, affected("tools/build/run-emulator.sh"))
