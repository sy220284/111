import hashlib
import os
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch
from v8_artifacts import names, verify, extract, direct_producer, producer_recipe

TARGET="aarch64-linux-android"
KEY="a"*64
SOURCE="b"*40
PROVENANCE='commit=2768994f664e8a6e3aba27503606c58339136e2a\ntarget='+TARGET+'\nv8_enable_sandbox = true\nv8_enable_pointer_compression = true\nv8_target_cpu = "arm64"\ntarget_os = "android"\n'

class V8ArtifactsTests(unittest.TestCase):
    def files(self,root):
        files=names(TARGET)
        (root/files[0]).write_bytes(b"!<arch>\nexample")
        (root/files[1]).write_text("bindings")
        (root/"BUILD_INPUTS").write_bytes(b"inputs")
        (root/"SOURCE_PROVENANCE").write_text(PROVENANCE)
        (root/"SHA256SUMS").write_text("".join(f"{hashlib.sha256((root/n).read_bytes()).hexdigest()}  {n}\n" for n in files[:-1]))
        return files

    def test_exact_files_and_hashes(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)
            files=self.files(root)
            self.assertEqual(files[:2],verify(root,TARGET,b"inputs"))
            for expected in (b"bad",):
                with self.assertRaises(ValueError): verify(root,TARGET,expected)
            (root/files[0]).write_bytes(b"wrong")
            with self.assertRaises(ValueError): verify(root,TARGET,b"inputs")
            self.files(root)
            (root/"extra").write_text("unexpected")
            with self.assertRaises(ValueError): verify(root,TARGET,b"inputs")

    def test_zip_paths_and_duplicate_names(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)
            src=root/"src";src.mkdir()
            files=self.files(src)
            archive=root/"data.zip"
            with zipfile.ZipFile(archive,"w") as z:
                for name in files: z.write(src/name,name)
            extract(archive,root/"valid",TARGET)
            verify(root/"valid",TARGET,b"inputs")
            with zipfile.ZipFile(archive,"w") as z:
                for name in files[:-1]: z.write(src/name,name)
                z.writestr("../outside","bad")
            with self.assertRaises(ValueError): extract(archive,root/"invalid",TARGET)
            self.assertFalse((root/"outside").exists())

    def test_only_original_successful_producer(self):
        at="2026-11-26T08:00:00Z"
        artifact={"name":"v8-binary-"+KEY,"id":3,"expired":False,"digest":"sha256:"+"1"*64,
                  "size_in_bytes":100,"created_at":at,"workflow_run":{"id":12}}
        run={"head_repository":{"full_name":"o/r"},"path":".github/workflows/ci.yml","event":"pull_request",
             "head_sha":SOURCE,"run_attempt":1,"status":"in_progress"}
        job={"name":"native / V8 官方源码产物 · arm64-v8a","head_sha":SOURCE,"run_attempt":1,
             "status":"completed","conclusion":"success","started_at":at,"completed_at":at,
             "steps":[{"name":"从官方源码编译 V8","conclusion":"success"}]}
        def check(j=job,a=artifact,r=run,ancestor=True):
            def api(repo,path):
                return r if path.endswith("/12") else {"total_count":1,"jobs":[j]}
            return direct_producer("o/r",a,TARGET,KEY,SOURCE,api,lambda *x:ancestor,lambda *x:KEY)
        with patch.dict(os.environ,{"GITHUB_RUN_ID":"0"}):
            self.assertTrue(check())
            self.assertFalse(check(dict(job,conclusion="failure")))
            self.assertFalse(check(dict(job,run_attempt=2)))
            self.assertFalse(check(dict(job,steps=[{"name":"从官方源码编译 V8","conclusion":"skipped"}])))
            self.assertFalse(check(a=dict(artifact,created_at="2026-11-26T09:00:00Z")))
            self.assertFalse(check(r=dict(run,head_repository={"full_name":"fork/r"})))
            self.assertFalse(check(ancestor=False))

    def test_v8_recipe_is_independent_of_core(self):
        workflow="jobs:\n  v8:\n    steps: compile\n  native:\n    steps: core\n"
        self.assertEqual(producer_recipe(workflow),producer_recipe(workflow.replace("steps: core","steps: changed")))
        self.assertNotEqual(producer_recipe(workflow),producer_recipe(workflow.replace("steps: compile","steps: changed")))
