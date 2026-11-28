#!/usr/bin/env python3
import hashlib
import pathlib
import tempfile
import unittest
import subprocess
from unittest.mock import patch
from prepare_prebuilt_developer_runtime import (
    GIT_DEB, GIT_VERSION, VERSIONS,
    fetch, patch_git_shell, package_records, pack_runtime_data,
)


class PrebuiltDeveloperRuntimeTests(unittest.TestCase):
    def test_runtime_pack_is_deterministic_and_deduplicates_exact_content(self):
        import os
        import zipfile
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            data = root / "data"
            assets = root / "assets"
            assets.mkdir()
            for name, content in (("git/lib/libsame.so", b"same"), ("python/lib/libsame.so", b"same"),
                                  ("python/lib/libalias.so.1", b"same"), ("node/lib/libsame.so", b"different")):
                path = data / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(content)
            pack_runtime_data(data, assets)
            first = (assets / "development-runtime.zip").read_bytes()
            manifest = (assets / "development-runtime.files.tsv").read_text()
            self.assertEqual(4, len(manifest.splitlines()))
            with zipfile.ZipFile(assets / "development-runtime.zip") as archive:
                self.assertEqual(["git/lib/libsame.so", "node/lib/libsame.so"], archive.namelist())
                self.assertEqual(b"different", archive.read("node/lib/libsame.so"))
            for path in data.rglob("*.so"):
                os.utime(path, (1700000000, 1700000000))
            pack_runtime_data(data, assets)
            self.assertEqual(first, (assets / "development-runtime.zip").read_bytes())
            self.assertEqual(manifest, (assets / "development-runtime.files.tsv").read_text())

    def test_777_version_lock(self):
        self.assertEqual(VERSIONS["python"], "3.14.10")
        self.assertEqual(VERSIONS["node"], "26.13.1")
        self.assertEqual(VERSIONS["git"], GIT_VERSION)

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

    def test_exact_git_shell_transformation(self):
        old = b"/data/data/com.termux/files/usr/bin/sh\0"
        result = patch_git_shell(b"header" + old + b"tail")
        self.assertEqual(len(result), len(b"header" + old + b"tail"))
        self.assertEqual(result, b"header" + b"/system/bin/sh".ljust(len(old), b"\0") + b"tail")
        for bad in (b"no shell", old + old):
            with self.assertRaises(ValueError):
                patch_git_shell(bad)


if __name__ == "__main__":
    unittest.main()
