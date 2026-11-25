#!/usr/bin/env python3
import hashlib
import pathlib
import tempfile
import unittest
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
