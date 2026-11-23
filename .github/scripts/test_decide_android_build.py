#!/usr/bin/env python3
"""Guard native-to-APK push deduplication against path-filter drift."""
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
from decide_android_build import NATIVE_PREFIXES, NATIVE_FILES, native_will_run, changed_paths


class AndroidBuildDecisionTests(unittest.TestCase):
    def test_native_source_or_tool_changes_defer_push_apk(self):
        for path in (*NATIVE_FILES, "upstream/codex/codex-rs/core/src/lib.rs",
                     "upstream/codex/.cargo/config.toml"):
            with self.subTest(path=path):
                self.assertTrue(native_will_run([path]))

    def test_android_ui_only_still_builds_apk(self):
        for path in ("android-app/app/src/main/java/MainActivity.kt",
                     "android-app/app/build.gradle.kts",
                     ".github/workflows/build-android.yml",
                     "android-app/tools/package_native_runtime.sh"):
            with self.subTest(path=path):
                self.assertFalse(native_will_run([path]))

    def test_unrelated_upstream_docs_do_not_defer_apk(self):
        self.assertFalse(native_will_run([
            "upstream/codex/README.md", "upstream/codex/.github/workflows/rust-ci.yml"
        ]))

    def test_native_trigger_paths_are_covered_by_workflow(self):
        source = (Path(__file__).resolve().parents[1] / "workflows" /
                  "build-native-codex.yml").read_text(encoding="utf-8")
        push_block = source.split("  push:", 1)[1].split("  pull_request:", 1)[0]
        for path in NATIVE_FILES:
            self.assertIn(f'- "{path}"', push_block)
        for prefix in NATIVE_PREFIXES:
            self.assertIn(f'- "{prefix}**"', push_block)

    def test_non_native_trigger_stays_out_of_deferral(self):
        source = (Path(__file__).resolve().parents[1] / "workflows" /
                  "build-native-codex.yml").read_text(encoding="utf-8")
        push_block = source.split("  push:", 1)[1].split("  pull_request:", 1)[0]
        paths = [
            line.strip().removeprefix('- "').removesuffix('"')
            for line in push_block.splitlines() if line.strip().startswith('- "')
        ]
        for item in paths:
            if item.endswith("**"):
                self.assertIn(item[:-2], NATIVE_PREFIXES)
            else:
                self.assertIn(item, NATIVE_FILES)

    def test_compare_uses_both_commits_and_detects_deletes(self):
        old = "a" * 40
        new = "b" * 40
        with patch("decide_android_build.subprocess.run") as run:
            run.return_value.stdout = b"android-app/app/src/main/New.kt\0upstream/codex/codex-rs/core/src/removed.rs\0"
            self.assertTrue(native_will_run(changed_paths(old, new)))
            cmd = run.call_args.args[0]
            self.assertIn("--no-renames", cmd)
            self.assertEqual(cmd[-3:], [old, new, "--"])

    def test_invalid_sha_is_error(self):
        with self.assertRaises(ValueError):
            changed_paths("broken", "a" * 40)


if __name__ == "__main__":
    unittest.main()
