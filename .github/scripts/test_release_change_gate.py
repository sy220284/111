#!/usr/bin/env python3
"""Offline tests for content-based Android release selection."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from release_change_gate import changes_apk_inputs, should_release


class StubSelector:
    def __init__(self, *, changed=False):
        self.changed = changed
        self.queries = []

    def signature(self, sha):
        self.queries.append(sha)
        return "new-native" if self.changed and sha == "head" else "same-native"


class ReleaseChangeGateTests(unittest.TestCase):
    def test_app_source_and_build_config_trigger_release(self):
        for path in (
            "android-app/app/src/main/java/MainActivity.kt",
            "android-app/app/src/main/AndroidManifest.xml",
            "android-app/app/src/release/res/values/strings.xml",
            "android-app/app/build.gradle.kts",
            "android-app/tools/package_native_runtime.sh",
            ".github/scripts/prepare-android-patcher.sh",
            ".github/release-version",
        ):
            with self.subTest(path=path):
                self.assertTrue(changes_apk_inputs([path]))

    def test_tests_docs_and_ci_triggers_do_not_create_version(self):
        paths = [
            "android-app/app/src/test/java/Test.kt",
            "android-app/app/src/androidTest/java/InstrumentedTest.kt",
            "android-app/tools/test_protocol_contract.py",
            ".github/workflows/build-native-codex.yml",
            ".github/workflows/check-android-ui.yml",
            "upstream/codex/.github/workflows/rust-ci.yml",
            "upstream/codex/sdk/typescript/src/index.ts",
            "upstream/codex/README.md",
            "CODEX_UPSTREAM_COMMIT",
            "docs/正式发布流程.md",
        ]
        selector = StubSelector()
        self.assertFalse(should_release(paths, "base", "head", selector))
        self.assertEqual(selector.queries, ["base", "head"])

    def test_native_build_input_change_creates_version(self):
        selector = StubSelector(changed=True)
        self.assertTrue(should_release(
            ["upstream/codex/codex-rs/core/src/lib.rs"], "base", "head", selector))
        self.assertEqual(selector.queries, ["base", "head"])

    def test_apk_change_does_not_need_native_lookup(self):
        selector = StubSelector()
        self.assertTrue(should_release(
            ["android-app/app/src/main/AndroidManifest.xml"], "base", "head", selector))
        self.assertFalse(selector.queries)


if __name__ == "__main__":
    unittest.main()
