import unittest
from runtime_artifacts import direct_producer

class RuntimeArtifactTests(unittest.TestCase):
    def test_only_direct_successful_matching_producer(self):
        source = "a"*40
        key = "b"*64
        artifact = {"name":"111-runtime-arm64-v8a-"+key, "expired":False, "id":23,
                    "size_in_bytes":100, "digest":"sha256:"+"c"*64,
                    "created_at":"2026-11-27T01:02:00Z", "workflow_run":{"id":12}}
        run = {"head_repository":{"full_name":"o/r"}, "path":".github/workflows/ci.yml",
               "event":"pull_request", "head_sha":source, "run_attempt":1}
        job = {"name":"runtime-arm64 / ARM64 工具链产物核验", "head_sha":source,
               "run_attempt":1, "status":"completed", "conclusion":"success",
               "started_at":"2026-11-27T01:00:00Z", "completed_at":"2026-11-27T01:03:00Z",
               "steps":[{"name":"生成可复用离线组件并回读","conclusion":"success"}]}
        def api(repo, path):
            return {"total_count":1,"jobs":[job]} if "/jobs?" in path else run
        def select(a=artifact, fingerprint=lambda *_: key, caller=lambda *_: True):
            return direct_producer("o/r", a, "arm64-v8a", source, key, api, fingerprint, caller)
        self.assertEqual(23, select()["artifact_id"])
        self.assertIsNone(select(dict(artifact, expired=True)))
        self.assertIsNone(select(dict(artifact, created_at="2026-11-27T01:04:00Z")))
        self.assertIsNone(select(fingerprint=lambda *_: "d"*64))
        self.assertIsNone(select(caller=lambda *_: False))
        job["steps"][0]["conclusion"]="skipped"
        self.assertIsNone(select())
        job["steps"][0]["conclusion"]="success"
        run["path"]=".github/workflows/dev-toolchain.yml"
        self.assertIsNone(select())
