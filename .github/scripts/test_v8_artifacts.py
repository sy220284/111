import hashlib
import os
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch
from v8_artifacts import names, verify, extract, direct_producer, producer_recipe, current_artifact, expected_inputs, INPUTS, host_identity

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

    def test_current_artifact_requires_unique_live_digest(self):
        row = {"id": 42, "name": "v8-binary-" + KEY, "expired": False,
               "digest": "sha256:" + "1"*64, "size_in_bytes": 100}
        def lookup(rows):
            def api(repo, path):
                self.assertEqual(path, "actions/runs/17/artifacts?name=v8-binary-" + KEY + "&per_page=100")
                return {"total_count": len(rows), "artifacts": rows}
            return current_artifact("o/r", 17, KEY, api)
        self.assertEqual(lookup([row])["id"], 42)
        for rows in ([], [dict(row, expired=True)], [row, dict(row, id=43)],
                     [dict(row, digest=None)], [dict(row, name="v8-binary-other")]):
            with self.subTest(rows=rows), self.assertRaises(ValueError):
                lookup(rows)

    def test_pristine_lock_and_environment_contract(self):
        import subprocess
        from types import SimpleNamespace
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            for name in INPUTS:
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("original")
            ndk = root / "ndk"
            ndk.mkdir()
            (ndk / "source.properties").write_text("Pkg.Revision = 27.2.12479018\n")
            subprocess.run(["git", "init", "-q", d], check=True)
            subprocess.run(["git", "-C", d, "add", "."], check=True)
            subprocess.run(["git", "-C", d, "-c", "user.name=CI", "-c", "user.email=ci@example.invalid",
                            "commit", "-qm", "fixture"], check=True)
            with patch.dict(os.environ, {"ANDROID_NDK_HOME": str(ndk)}), \
                 patch("v8_artifacts.subprocess.check_output", return_value=b"rustc 1.99.1 (fixture)\n"), \
                 patch("v8_artifacts.host_identity", return_value=b"runner=x86_64\nID=ubuntu\nVERSION_ID=24.04\n"):
                original = expected_inputs(root, TARGET)
                self.assertEqual(original, expected_inputs(root, TARGET))
                self.assertNotEqual(original, expected_inputs(root, "x86_64-linux-android"))
                (root / "upstream/codex/codex-rs/Cargo.lock").write_text("Android-adapted")
                with self.assertRaises(subprocess.CalledProcessError):
                    expected_inputs(root, TARGET)
            for data in ('ID=ubuntu\nVERSION_ID="24.04"\nPRETTY_NAME=old\n',
                         'ID=ubuntu\nVERSION_ID="24.04"\nPRETTY_NAME=new\n'):
                with patch("v8_artifacts.Path.read_text", return_value=data), \
                     patch("v8_artifacts.os.uname", return_value=SimpleNamespace(machine="x86_64")):
                    self.assertEqual(host_identity(), b"runner=x86_64\nID=ubuntu\nVERSION_ID=24.04\n")
            with patch("v8_artifacts.Path.read_text", return_value="ID=ubuntu\nVERSION_ID=26.04\n"), \
                 patch("v8_artifacts.os.uname", return_value=SimpleNamespace(machine="x86_64")):
                with self.assertRaises(ValueError):
                    host_identity()

    def test_real_workflow_checks_before_adapter_and_supersedes_old_head(self):
        root = Path(__file__).resolve().parents[2]
        workflow = (root / ".github/workflows/build-native-codex.yml").read_text()
        native = workflow.split("\n  native:\n", 1)[1].split("\n  provider-contract:\n", 1)[0]
        self.assertLess(native.index("v8_artifacts.py consume"), native.index("cat >> upstream/codex/codex-rs/core/Cargo.toml"))
        self.assertLess(native.index("v8_artifacts.py consume"), native.index("prepare_android_remote_delivery.py\" \"$GITHUB_WORKSPACE"))
        self.assertIn("overwrite: true", workflow.split("\n  native:\n", 1)[0])
        producer = (root / "android-app/tools/prepare_android_v8.sh").read_text()
        self.assertIn("v8_artifacts.py\" inputs --target", producer)
        ci = (root / ".github/workflows/ci.yml").read_text()
        self.assertIn("github.event_name == 'pull_request' && 'latest' || github.sha", ci)
        self.assertIn("cancel-in-progress: ${{ github.event_name == 'pull_request' }}", ci)
