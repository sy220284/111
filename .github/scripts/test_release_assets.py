#!/usr/bin/env python3
"""Exercise release assets with a real ZIP and matching source artifact, offline."""
import hashlib
import json
import os
import subprocess
import tarfile
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile

SCRIPT = Path(__file__).with_name("prepare-release-assets.sh").resolve()


class ReleaseAssetTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.runner = self.root / "runner"
        self.runner.mkdir()
        self.apk_dir = self.root / "android-app/app/build/outputs/apk/release"
        self.apk_dir.mkdir(parents=True)
        self.apk = self.apk_dir / "app-arm64-v8a-release.apk"
        self.source = self.runner / "git-corresponding-source.tar.xz"
        notice = self.runner / "COPYING"
        notice.write_text("test corresponding source")
        with tarfile.open(self.source, "w:xz") as archive:
            archive.add(notice, arcname="COPYING")
        self.binaries = {"libcodex_git.so": b"git", "libcodex_git_remote_http.so": b"helper"}
        self.provenance = {"abi": "arm64-v8a", "gitHttps": True, "gitCorrespondingSourceVerified": True,
                           "git": {"sourceArchiveSha256": hashlib.sha256(self.source.read_bytes()).hexdigest(),
                                   "binaries": {name: hashlib.sha256(data).hexdigest()
                                                for name, data in self.binaries.items()}}}
        self.env = dict(os.environ, GITHUB_WORKSPACE=str(self.root), RUNNER_TEMP=str(self.runner),
                        GITHUB_REPOSITORY="test/111", GH_TOKEN="unused-for-first-release",
                        RELEASE_VERSION="0.1.0-111.1", RELEASE_TAG="v0.1.0-111.1", PREVIOUS_TAG="")

    def package(self, provenance=True):
        with ZipFile(self.apk, "w") as archive:
            for name, data in self.binaries.items():
                archive.writestr("lib/arm64-v8a/" + name, data)
            if provenance:
                archive.writestr("assets/development-runtime/development-runtime.provenance.json",
                                 json.dumps(self.provenance))

    def run_release(self, check=True):
        return subprocess.run(["bash", str(SCRIPT)], cwd=self.root, env=self.env,
                              check=check, capture_output=True, text=True)

    def test_first_release_contains_only_verified_phone_apk_and_manifest(self):
        self.package()
        (self.apk_dir / "app-x86_64-release.apk").write_bytes(b"not a phone APK")
        self.run_release()
        out = self.root / ".release-assets"
        manifest = json.loads((out / "update-manifest.json").read_text())
        self.assertEqual(manifest["schema"], 2)
        self.assertEqual(manifest["targetVersion"], "0.1.0-111.1")
        self.assertEqual(manifest["patches"], [])
        self.assertEqual({a["abi"] for a in manifest["apks"]}, {"arm64-v8a"})
        for entry in manifest["apks"]:
            data = (out / entry["name"]).read_bytes()
            self.assertEqual(data, self.apk.read_bytes())
            self.assertEqual(entry["size"], len(data))
            self.assertEqual(entry["sha256"], hashlib.sha256(data).hexdigest())
        self.assertEqual({p.name for p in out.iterdir()},
                         {"codex-android-arm64-v8a-0.1.0-111.1.apk", "update-manifest.json",
                          "SHA256SUMS.txt", "git-corresponding-source.tar.xz"})
        self.assertEqual((out / self.source.name).read_bytes(), self.source.read_bytes())
        sums = (out / "SHA256SUMS.txt").read_text()
        for name in ("git-corresponding-source.tar.xz", "update-manifest.json", manifest["apks"][0]["name"]):
            self.assertIn(name, sums)

    def test_missing_or_unverified_provenance_blocks_release(self):
        self.package(provenance=False)
        self.assertNotEqual(self.run_release(check=False).returncode, 0)
        self.provenance["gitCorrespondingSourceVerified"] = False
        self.package()
        self.assertNotEqual(self.run_release(check=False).returncode, 0)

    def test_tampered_binary_or_source_blocks_release(self):
        self.binaries["libcodex_git.so"] = b"changed"
        self.package()
        self.assertIn("binary SHA-256 mismatch", self.run_release(check=False).stderr)
        self.binaries["libcodex_git.so"] = b"git"
        self.package()
        self.source.write_bytes(b"changed")
        self.assertIn("source archive SHA-256 mismatch", self.run_release(check=False).stderr)


if __name__ == "__main__":
    unittest.main()
