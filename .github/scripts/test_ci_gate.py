import unittest
import json
from ci_reuse import required_groups, actual_jobs, SCHEMA
from ci_gate import verify, FULL, ALL

def fixture(full=True, runtime=None, pr=True, paths=()):
    if runtime is None:
        runtime = full
    jobs = {k: {"result": "skipped"} for k in ALL}
    groups=required_groups(full,runtime,"pull_request" if pr else "push","refs/pull/1/merge" if pr else "refs/heads/main", paths=paths)
    jobs["plan"] = {"result": "success", "outputs": {"full": str(full).lower(), "runtime": str(runtime).lower(), "receipt":json.dumps({"schema":SCHEMA,"source":"head","tree":"tree","required":sorted(groups),"execute":sorted(groups),"reused":{}})}}
    for k in actual_jobs(groups):
        jobs[k]["result"] = "success"
    return jobs

class GateTests(unittest.TestCase):
    def test_pr_and_main(self):
        self.assertEqual(verify(fixture(), "pull_request", "refs/pull/1/merge"), FULL | {"release-signing-smoke"})
        self.assertEqual(verify(fixture(pr=False), "push", "refs/heads/main"), FULL)
    def test_docs_only(self):
        self.assertEqual(verify(fixture(full=False), "pull_request", "refs/pull/1/merge"), set())

    def test_ci_signing_change_cannot_pass_with_signature_job_skipped(self):
        paths = [".github/scripts/VerifyApkSigning.java"]
        data = fixture(full=False, paths=paths)
        self.assertIn("release-signing-smoke", verify(data, "pull_request", "refs/pull/1/merge", paths))
        data["release-signing-smoke"]["result"] = "skipped"
        with self.assertRaises(ValueError):
            verify(data, "pull_request", "refs/pull/1/merge", paths)
    def test_failure_cancel_and_skipped_block_each_required_job(self):
        for job in FULL | {"release-signing-smoke"}:
            for result in ("failure", "cancelled", "skipped"):
                with self.subTest(job=job, result=result):
                    data = fixture()
                    data[job]["result"] = result
                    with self.assertRaises(ValueError): verify(data, "pull_request", "refs/pull/1/merge")
    def test_runtime_and_dispatch(self):
        jobs = fixture(runtime=True)
        self.assertIn("runtime-test", verify(jobs, "workflow_dispatch", "refs/heads/fix"))
        jobs["runtime-test"]["result"] = "skipped"
        with self.assertRaises(ValueError): verify(jobs, "workflow_dispatch", "refs/heads/fix")
    def test_missing_or_new_job_fails_closed(self):
        jobs = fixture()
        jobs.pop("build")
        with self.assertRaises(ValueError): verify(jobs, "pull_request", "refs/pull/1/merge")
        jobs = fixture()
        jobs["unknown"] = {"result": "success"}
        with self.assertRaises(ValueError): verify(jobs, "pull_request", "refs/pull/1/merge")
    def test_incomplete_plan_fails(self):
        jobs = fixture()
        jobs["plan"]["outputs"].pop("runtime")
        with self.assertRaises(ValueError): verify(jobs, "pull_request", "refs/pull/1/merge")

    def test_reused_group_must_skip_and_no_group_can_disappear(self):
        jobs=fixture()
        record=json.loads(jobs["plan"]["outputs"]["receipt"])
        record["execute"].remove("ui")
        record["reused"]["ui"]={"run_id":12}
        jobs["plan"]["outputs"]["receipt"]=json.dumps(record)
        for name in ("ui-emulator","ui-collect","ui-evidence"):
            jobs[name]["result"]="skipped"
        self.assertNotIn("ui-emulator",verify(jobs,"pull_request","refs/pull/1/merge"))
        record["reused"]={}
        jobs["plan"]["outputs"]["receipt"]=json.dumps(record)
        with self.assertRaises(ValueError): verify(jobs,"pull_request","refs/pull/1/merge")

    def test_full_acceptance_cannot_omit_real_runtime(self):
        data = fixture()
        data["plan"]["outputs"]["runtime"] = "false"
        with self.assertRaises(ValueError):
            verify(data, "pull_request", "refs/pull/1/merge")

    def test_reused_compile_source_is_bound_to_receipt(self):
        jobs=fixture()
        record=json.loads(jobs["plan"]["outputs"]["receipt"])
        record["execute"].remove("compile")
        record["reused"]["compile"]={"run_id":12,"artifacts":{"codex-android-ui-apks":{"id":21},"ui-apk-integrity":{"id":22}}}
        jobs["verify-ui"]["result"]="skipped"
        jobs["plan"]["outputs"]["receipt"]=json.dumps(record)
        jobs["plan"]["outputs"]["compile_run_id"]="13"
        with self.assertRaises(ValueError): verify(jobs,"pull_request","refs/pull/1/merge")
        jobs["plan"]["outputs"]["compile_run_id"]="12"
        jobs["plan"]["outputs"]["compile_apk_id"]="21"
        jobs["plan"]["outputs"]["compile_integrity_id"]="23"
        with self.assertRaises(ValueError): verify(jobs,"pull_request","refs/pull/1/merge")
        jobs["plan"]["outputs"]["compile_integrity_id"]="22"
        self.assertIn("ui-emulator",verify(jobs,"pull_request","refs/pull/1/merge"))

    def test_runtime_only_reuses_arm64_package_without_rebuilding(self):
        data=fixture()
        record=json.loads(data["plan"]["outputs"]["receipt"])
        record["execute"].remove("package")
        record["reused"]["package"]={"run_id":12}
        data["plan"]["outputs"]["receipt"]=json.dumps(record)
        for job in ("runtime-arm64", "build", "verify-apk"):
            data[job]["result"]="skipped"
        required=verify(data,"pull_request","refs/pull/1/merge")
        self.assertIn("native", required)
        self.assertIn("runtime-test", required)
        self.assertNotIn("build", required)
        data["native"]["result"]="skipped"
        with self.assertRaises(ValueError):
            verify(data,"pull_request","refs/pull/1/merge")
