#!/usr/bin/env python3
import hashlib
import pathlib
import tempfile
import unittest
import subprocess
from unittest.mock import patch
from prepare_prebuilt_developer_runtime import (
    GIT_DEB, GIT_VERSION, PIP, PIP_WHEEL, VSCODROID_COMMIT,
    fetch, patch_git_shell, verified_git_manifest,
)


class PrebuiltDeveloperRuntimeTests(unittest.TestCase):
    def test_wheel_pin_uses_official_wheel_not_source_digest(self):
        self.assertTrue(PIP_WHEEL[0].endswith(f"/pip-{PIP}-py3-none-any.whl"))
        self.assertEqual(PIP_WHEEL[1], "f3b60b20ffaadd4415ed7957212a38ac63fa4c5fa375bd0f4a797777940fad13")

    def test_cached_file_still_requires_exact_digest(self):
        with tempfile.TemporaryDirectory() as temp:
            path = pathlib.Path(temp) / "example.whl"
            path.write_bytes(b"wheel")
            expected = hashlib.sha256(b"wheel").hexdigest()
            self.assertEqual(fetch("https://example.com/example.whl", expected, path.parent), path)
            path.write_bytes(b"changed")
            with self.assertRaisesRegex(ValueError, "Digest mismatch"):
                fetch("https://example.com/example.whl", expected, path.parent)

    def test_download_retries_tls_and_only_publishes_verified_bytes(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            url = "https://example.com/git.tar.xz"
            expected = hashlib.sha256(b"verified").hexdigest()
            def download(command, check):
                self.assertTrue(check)
                self.assertIn("--retry-all-errors", command)
                self.assertEqual(command[command.index("--proto") + 1], "=https")
                self.assertEqual(command[command.index("--proto-redir") + 1], "=https")
                self.assertEqual(command[command.index("--retry") + 1], "3")
                pathlib.Path(command[-1]).write_bytes(b"verified")
            with patch("prepare_prebuilt_developer_runtime.subprocess.run", side_effect=download):
                self.assertEqual(fetch(url, expected, root).read_bytes(), b"verified")
            self.assertFalse((root / "git.tar.xz.partial").exists())

    def test_failed_or_corrupt_download_never_publishes_partial_file(self):
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            url = "https://example.com/git.tar.xz"
            expected = hashlib.sha256(b"verified").hexdigest()
            def transport_error(command, check):
                pathlib.Path(command[-1]).write_bytes(b"incomplete")
                raise subprocess.CalledProcessError(35, command)
            with patch("prepare_prebuilt_developer_runtime.subprocess.run", side_effect=transport_error):
                with self.assertRaises(subprocess.CalledProcessError):
                    fetch(url, expected, root)
            self.assertEqual(list(root.iterdir()), [])
            def corrupt(command, check):
                pathlib.Path(command[-1]).write_bytes(b"corrupt")
            with patch("prepare_prebuilt_developer_runtime.subprocess.run", side_effect=corrupt):
                with self.assertRaisesRegex(ValueError, "Digest mismatch"):
                    fetch(url, expected, root)
            self.assertEqual(list(root.iterdir()), [])

    def test_exact_git_manifest_and_shell_transformation(self):
        with tempfile.TemporaryDirectory() as temp:
            manifest = pathlib.Path(temp) / "build-manifest.txt"
            good = f"app-commit\t{VSCODROID_COMMIT}\ntermux\tgit\t{GIT_VERSION}\t{GIT_DEB[1]}\n"
            manifest.write_text(good)
            verified_git_manifest(manifest)
            for bad in (good.replace(GIT_VERSION, "2.52.0"), good + good,
                        good + "# NOTE git was resolved twice in this build\n"):
                manifest.write_text(bad)
                with self.assertRaises(ValueError):
                    verified_git_manifest(manifest)
        old = b"/data/data/com.termux/files/usr/bin/sh\0"
        result = patch_git_shell(b"header" + old + b"tail")
        self.assertEqual(len(result), len(b"header" + old + b"tail"))
        self.assertEqual(result, b"header" + b"/system/bin/sh".ljust(len(old), b"\0") + b"tail")
        for bad in (b"no shell", old + old):
            with self.assertRaises(ValueError):
                patch_git_shell(bad)


if __name__ == "__main__":
    unittest.main()
