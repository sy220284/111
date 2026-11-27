import hashlib
import os
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch
from v8_artifacts import names, verify, extract, direct_producer, producer_recipe, current_artifact, expected_inputs, INPUTS, LOCK_PATH, VERSION, v8_lock_identity, compiler_action_recipe, host_identity, restore, historical_artifacts

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

    def test_recovered_artifact_has_same_strict_direct_producer_contract(self):
        at="2026-11-26T08:00:00Z"
        artifact={"name":"v8-recovery-"+KEY,"id":3,"expired":False,"digest":"sha256:"+"1"*64,
                  "size_in_bytes":100,"created_at":at,"workflow_run":{"id":12}}
        run={"head_repository":{"full_name":"o/r"},"path":".github/workflows/ci.yml","event":"pull_request",
             "head_sha":SOURCE,"run_attempt":1,"status":"completed"}
        job={"name":"native / 编译 arm64-v8a 核心","head_sha":SOURCE,"run_attempt":1,
             "status":"completed","conclusion":"success","started_at":at,"completed_at":at,
             "steps":[{"name":"从固定官方源码重建并核验 V8 产物","conclusion":"success"}]}
        def check(j):
            def api(repo,path):
                return run if path.endswith("/12") else {"total_count":1,"jobs":[j]}
            return direct_producer("o/r",artifact,TARGET,KEY,SOURCE,api,lambda *x:True,lambda *x:KEY)
        with patch.dict(os.environ,{"GITHUB_RUN_ID":"0"}):
            self.assertTrue(check(job))
            self.assertFalse(check(dict(job,conclusion="failure")))
            self.assertFalse(check(dict(job,steps=[{"name":"从固定官方源码重建并核验 V8 产物","conclusion":"skipped"}])))

    def test_restore_checks_recovery_if_original_missing(self):
        with tempfile.TemporaryDirectory() as d, \
             patch("v8_artifacts.api", side_effect=[{"artifacts":[]},{"artifacts":[{"id":7,"workflow_run":{"id":12}}]}]), \
             patch("v8_artifacts.direct_producer", return_value=True), \
             patch("v8_artifacts.download_verified") as download:
            self.assertTrue(restore("o/r",TARGET,SOURCE,Path(d)/"result",KEY,b"inputs"))
            self.assertEqual(download.call_count,1)

    def test_v8_recipe_is_independent_of_core(self):
        workflow="jobs:\n  v8:\n    steps: compile\n  native:\n    steps: core\n"
        self.assertEqual(producer_recipe(workflow),producer_recipe(workflow.replace("steps: core","steps: changed")))
        self.assertNotEqual(producer_recipe(workflow),producer_recipe(workflow.replace("steps: compile","steps: changed")))

    def test_bad_artifacts_are_excluded_and_rebuilt_after_two(self):
        rows = [{"id": 1}, {"id": 2}, {"id": 3}]
        with tempfile.TemporaryDirectory() as d, \
             patch("v8_artifacts.api", return_value={"artifacts": rows}), \
             patch("v8_artifacts.direct_producer", return_value=True), \
             patch("v8_artifacts.download_verified", side_effect=ValueError("corrupt")) as download:
            self.assertFalse(restore("o/r", TARGET, SOURCE, Path(d)/"result", KEY, b"inputs"))
            self.assertEqual(download.call_count, 2)
            self.assertFalse((Path(d)/"result").exists())

    def test_v8_timeout_is_not_a_build_input(self):
        workflow = "jobs:\n  v8:\n    timeout-minutes: 180\n    runs-on: ubuntu-24.04\n    steps: compile\n  native:\n"
        self.assertEqual(producer_recipe(workflow), producer_recipe(workflow.replace("180", "360")))
        self.assertEqual(producer_recipe(workflow), producer_recipe(workflow.replace("    timeout-minutes: 180", "    concurrency:\n      group: v8\n      queue: max\n    timeout-minutes: 360")))

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
            lock = root / LOCK_PATH
            lock.parent.mkdir(parents=True, exist_ok=True)
            lock.write_text(f'[[package]]\nname = "v8"\nversion = "{VERSION}"\n')
            ndk = root / "ndk"
            ndk.mkdir()
            (ndk / "source.properties").write_text("Pkg.Revision = 30.0.16248370\n")
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


    def test_complete_core_fetch_and_scoped_bindgen(self):
        root = Path(__file__).resolve().parents[2]
        workflow = (root / ".github/workflows/build-native-codex.yml").read_text()
        native = workflow.split("\n  native:\n", 1)[1].split("\n  provider-contract:\n", 1)[0]
        self.assertNotIn("cargo fetch --locked --target", native)
        self.assertLess(native.index('prepare_android_remote_delivery.py" "$GITHUB_WORKSPACE'), native.index("cargo fetch --locked\n"))
        self.assertLess(native.index("cargo fetch --locked\n"), native.index("cargo fetch --locked --offline"))
        self.assertLess(native.index("cargo fetch --locked --offline"), native.index("cargo build --locked --offline"))
        self.assertIn("cache-scope: workspace", native)
        self.assertIn("cache-scope: v8", workflow.split("\n  native:\n", 1)[0])
        shell = (root / "android-app/tools/prepare_android_v8.sh").read_text()
        self.assertNotIn('export "BINDGEN_EXTRA_CLANG_ARGS', shell)
        self.assertIn("export RUSTY_V8_ANDROID_BINDGEN_TARGET=", shell)
        self.assertIn("export RUSTY_V8_ANDROID_BINDGEN_SYSROOT=", shell)

    def test_v8_package_identity_ignores_unrelated_crates(self):
        lock = f'[[package]]\nname="v8"\nversion="{VERSION}"\nsource="registry+https://example.invalid"\nchecksum="abc"\n'
        self.assertEqual(v8_lock_identity(lock), v8_lock_identity(lock + '[[package]]\nname="unrelated"\nversion="3.0.0"\n'))
        self.assertNotEqual(v8_lock_identity(lock), v8_lock_identity(lock.replace('checksum="abc"', 'checksum="xyz"')))
        with self.assertRaises(ValueError):
            v8_lock_identity(lock.replace(VERSION, "0.0.1"))

    def test_cache_transport_is_not_compiler_input(self):
        action = "runs:\n  using: composite\n  steps:\n    - uses: dtolnay/rust-toolchain@sha\n      with:\n        toolchain: 1.99.1\n    - uses: actions/cache@sha\n      with:\n        key: old-cache\n"
        self.assertEqual(compiler_action_recipe(action), compiler_action_recipe(action.replace("old-cache","new-cache")))
        self.assertNotEqual(compiler_action_recipe(action), compiler_action_recipe(action.replace("1.99.1","1.99.2")))

    def test_probe_only_selects_original_descriptor_without_download(self):
        with patch("v8_artifacts.api", return_value={"artifacts":[{"id":9}]}), \
             patch("v8_artifacts.direct_producer", return_value=True), \
             patch("v8_artifacts.download_verified") as download:
            self.assertEqual({"id":9}, next(historical_artifacts("o/r", TARGET, SOURCE, KEY)))
            download.assert_not_called()

    def test_current_artifact_may_be_absent_when_historical_producer_exists(self):
        self.assertIsNone(current_artifact("o/r", 17, KEY,
            lambda *_: {"total_count":0,"artifacts":[]}, allow_missing=True))

    def test_manual_prewarm_keeps_full_product_gate_and_cache_scope(self):
        root = Path(__file__).resolve().parents[2]
        workflow = (root / ".github/workflows/build-native-codex.yml").read_text()
        producer, consumer = workflow.split("\n  v8:\n", 1)[1].split("\n  native:\n", 1)
        recovery = consumer.split("\n  provider-contract:\n", 1)[0]
        for block in (producer, recovery):
            self.assertIn("SCCACHE_CLIENT_SIDE: \"1\"", block)
            self.assertIn("SCCACHE_GHA_VERSION: ${{ matrix.v8_key }}", block)
            self.assertIn("version: v0.20.1", block)
            self.assertIn("'READ_ONLY' || 'READ_WRITE'", block)
        self.assertIn("v8_only:", workflow)
        self.assertIn("!(github.event_name == 'workflow_dispatch' && inputs.v8_only)", recovery)
        self.assertIn('test "$V8" = success', workflow)
        self.assertIn('test "$NATIVE" = skipped', workflow)
        self.assertIn('test "$NATIVE" = success; test "$V8" = success', workflow)
        shell = (root / "android-app/tools/prepare_android_v8.sh").read_text()
        self.assertIn('--jobs v8', shell)
        self.assertIn('v8_enable_sandbox = true', shell)
        self.assertIn('v8_enable_pointer_compression = true', shell)
