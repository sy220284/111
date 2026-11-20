#!/usr/bin/env python3
"""Test both ABI release assets without external services or signing material."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("prepare-release-assets.sh").resolve()


class ReleaseAssetTests(unittest.TestCase):
    def test_first_release_contains_two_verified_apks_and_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk_dir = root / "android-app/app/build/outputs/apk/release"
            apk_dir.mkdir(parents=True)
            source_apks = {}
            for abi in ("arm64-v8a", "x86_64"):
                data = ("signed-fixture:" + abi).encode("utf-8")
                source_apks[abi] = data
                (apk_dir / f"app-{abi}-release.apk").write_bytes(data)
            runner = root / "runner"
            runner.mkdir()
            env = dict(os.environ)
            env.update(
                GITHUB_WORKSPACE=str(root),
                RUNNER_TEMP=str(runner),
                GITHUB_REPOSITORY="test/111",
                GH_TOKEN="unused-for-first-release",
                RELEASE_VERSION="0.1.0-111.1",
                RELEASE_TAG="v0.1.0-111.1",
                PREVIOUS_TAG="",
            )
            subprocess.run(["bash", str(SCRIPT)], cwd=root, env=env, check=True, capture_output=True, text=True)
            out = root / ".release-assets"
            manifest = json.loads((out / "update-manifest.json").read_text(encoding="utf-8"))
            self.assertEqual(manifest["schema"], 2)
            self.assertEqual(manifest["targetVersion"], "0.1.0-111.1")
            self.assertEqual(manifest["patches"], [])
            self.assertEqual({a["abi"] for a in manifest["apks"]}, set(source_apks))
            for entry in manifest["apks"]:
                data = (out / entry["name"]).read_bytes()
                self.assertEqual(data, source_apks[entry["abi"]])
                self.assertEqual(entry["size"], len(data))
                self.assertEqual(entry["sha256"], hashlib.sha256(data).hexdigest())
            sums = (out / "SHA256SUMS.txt").read_text(encoding="utf-8")
            self.assertIn("update-manifest.json", sums)
            for entry in manifest["apks"]:
                self.assertIn(entry["name"], sums)


if __name__ == "__main__":
    unittest.main()
