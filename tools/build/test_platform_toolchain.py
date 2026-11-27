import hashlib
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile

import platform_toolchain as toolchain


class PlatformToolchainTest(unittest.TestCase):
    def test_sdk_uses_archive_digest_and_correct_package_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / "adb.zip"
            with ZipFile(archive, "w") as zipped:
                zipped.writestr("platform-tools/adb", "adb")
                zipped.writestr("platform-tools/source.properties", "Pkg.Revision=37.0.3\n")
            candidate = {**toolchain.ANDROID["adb"], "sha256": "0" * 64}

            def fake_curl(command, **options):
                if command[0] == "curl":
                    shutil.copyfile(archive, command[-1])
                else:
                    real_run(command, **options)

            real_run = toolchain.subprocess.run
            with patch.dict(toolchain.ANDROID, {"adb": candidate}), patch.object(
                toolchain.subprocess, "run", side_effect=fake_curl,
            ) as run:
                sdk = root / "sdk"
                with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
                    toolchain.install_android(sdk, "adb")
                self.assertFalse((sdk / "platform-tools").exists())
                candidate["sha256"] = hashlib.sha256(archive.read_bytes()).hexdigest()
                installed = toolchain.install_android(sdk, "adb")
                self.assertTrue(toolchain.android_available(installed, candidate))
                self.assertIn('path="platform-tools"', (installed / "package.xml").read_text())
                before = run.call_count
                toolchain.install_android(sdk, "adb")
                self.assertEqual(run.call_count, before)

    def test_unrelated_host_version_does_not_invalidate_v8_recipe(self):
        options = {"host": ("jdk", "python"), "android": toolchain.PROFILES["v8"]}
        before = toolchain.recipe(json.dumps(toolchain.LOCK), **options)
        modified = json.loads(json.dumps(toolchain.LOCK))
        modified["host"]["gh"]["version"] = "different"
        self.assertEqual(before, toolchain.recipe(json.dumps(modified), **options))
        modified["android"]["ndk"]["version"] = "different"
        self.assertNotEqual(before, toolchain.recipe(json.dumps(modified), **options))
