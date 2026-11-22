#!/usr/bin/env python3
"""Test ARM64-only release assets without external services or signing material."""
import hashlib
import json
import os
import tarfile
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("prepare-release-assets.sh").resolve()


class ReleaseAssetTests(unittest.TestCase):
    def test_first_release_contains_only_verified_phone_apk_and_manifest(self):
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
            # Stub the source fetch in this isolated unit test. Production
            # still verifies the exact Git release commit, with no fallback.
            fake_bin = root / "fake-bin"
            fake_bin.mkdir()
            git = fake_bin / "git"
            git.write_text(
                "#!/usr/bin/env python3\n"
                "import pathlib, sys\n"
                "if sys.argv[1] == 'clone':\n"
                "    target = pathlib.Path(sys.argv[-1])\n"
                "    target.mkdir(parents=True, exist_ok=True)\n"
                "    (target / 'COPYING').write_text('corresponding test source')\n"
                "elif sys.argv[1] == '-C' and sys.argv[-2:] == ['rev-parse', 'HEAD']:\n"
                "    print('9a2fb147f2c61d0cab52c883e7e26f5b7948e3ed')\n"
                "else:\n"
                "    sys.exit(2)\n", encoding="utf-8")
            git.chmod(0o755)
            env = dict(os.environ)
            env["PATH"] = str(fake_bin) + os.pathsep + env["PATH"]
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
            self.assertEqual({a["abi"] for a in manifest["apks"]}, {"arm64-v8a"})
            for entry in manifest["apks"]:
                data = (out / entry["name"]).read_bytes()
                self.assertEqual(data, source_apks[entry["abi"]])
                self.assertEqual(entry["size"], len(data))
                self.assertEqual(entry["sha256"], hashlib.sha256(data).hexdigest())
            self.assertEqual(
                {p.name for p in out.iterdir()},
                {"codex-android-arm64-v8a-0.1.0-111.1.apk", "update-manifest.json",
                 "SHA256SUMS.txt", "git-2.52.0-corresponding-source.tar.xz"},
            )
            with tarfile.open(out / "git-2.52.0-corresponding-source.tar.xz", "r:xz") as archive:
                self.assertIn("git-android-release-source/COPYING", archive.getnames())
            sums = (out / "SHA256SUMS.txt").read_text(encoding="utf-8")
            self.assertIn("git-2.52.0-corresponding-source.tar.xz", sums)
            self.assertIn("update-manifest.json", sums)
            for entry in manifest["apks"]:
                self.assertIn(entry["name"], sums)


if __name__ == "__main__":
    unittest.main()
