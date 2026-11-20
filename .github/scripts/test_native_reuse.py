#!/usr/bin/env python3
"""Offline regression tests for safe native artifact reuse."""
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from select_native_artifacts import ArtifactSelector, SelectionError, native_recipe


COMPILER_JOB = """
name: native
on:
  push:
    paths: [x]
jobs:
  native:
    name: Native
    needs: reuse
    if: needs.reuse.outputs.use != 'true'
    runs-on: ubuntu-latest
    timeout-minutes: 115
    strategy:
      matrix: {abi: [arm64-v8a, x86_64]}
    env:
      RUST: '1.99.1'
    steps:
      - name: 构建
        run: cargo build --locked
      - name: 上传原生内核与构建清单
        uses: actions/upload-artifact@v4
        with:
          retention-days: 90
"""


class FakeRunner:
    def __init__(self, *, changed_source=False, changed_recipe=False,
                 expired_first=False, missing_abi=False, ancestor=True):
        self.changed_source = changed_source
        self.changed_recipe = changed_recipe
        self.expired_first = expired_first
        self.missing_abi = missing_abi
        self.ancestor = ancestor
        self.called_api = 0

    def __call__(self, args):
        if args[:2] == ["git", "rev-parse"]:
            ref = args[2]
            is_old = ref.startswith(("old:", "older:"))
            is_upstream = ref.endswith(":upstream/codex")
            if is_upstream and is_old and self.changed_source:
                return "old-tree"
            return "same-tree" if is_upstream else "same-patcher"
        if args[:2] == ["git", "show"]:
            ref = args[2]
            if ref.endswith(":CODEX_UPSTREAM_COMMIT"):
                return "official-commit\n2026-11-20"
            if ref.endswith("build-native-codex.yml"):
                if ref.startswith(("old:", "older:")) and self.changed_recipe:
                    return COMPILER_JOB.replace("cargo build --locked", "cargo build --release")
                return COMPILER_JOB
        if args[:2] == ["git", "merge-base"]:
            return args[2] if self.ancestor else "other-history"
        if args[:3] == ["gh", "run", "list"]:
            return json.dumps([
                {"databaseId": 21, "headSha": "old", "conclusion": "success"},
                {"databaseId": 20, "headSha": "older", "conclusion": "success"},
            ])
        if args[:2] == ["gh", "api"]:
            self.called_api += 1
            names = ["codex-android-native-arm64-v8a"]
            if not self.missing_abi:
                names.append("codex-android-native-x86_64")
            return json.dumps({"artifacts": [
                {"name": name, "expired": self.expired_first and self.called_api == 1,
                 "size_in_bytes": 1024} for name in names
            ]})
        raise AssertionError(f"unexpected: {args}")


class NativeReuseTests(unittest.TestCase):
    def test_trigger_and_upload_changes_do_not_invalidate_binary(self):
        edited = COMPILER_JOB.replace("paths: [x]", "paths: [y]")
        edited = edited.replace("retention-days: 90", "retention-days: 30")
        self.assertEqual(native_recipe(COMPILER_JOB), native_recipe(edited))

    def test_compiler_change_invalidates_binary(self):
        edited = COMPILER_JOB.replace("cargo build --locked", "cargo build --release")
        self.assertNotEqual(native_recipe(COMPILER_JOB), native_recipe(edited))

    def test_unrecognized_recipe_is_error(self):
        with self.assertRaises(SelectionError):
            native_recipe("jobs:\n  other:\n")

    def test_reuse_both_architectures(self):
        runner = FakeRunner()
        self.assertEqual(ArtifactSelector(runner).find("owner/repo", "HEAD",
                                                       ["arm64-v8a", "x86_64"]), "21")
        self.assertEqual(runner.called_api, 1)

    def test_fallback_to_older_unexpired_artifacts(self):
        runner = FakeRunner(expired_first=True)
        self.assertEqual(ArtifactSelector(runner).find("owner/repo", "HEAD",
                                                       ["arm64-v8a", "x86_64"]), "20")

    def test_missing_architecture_cannot_reuse_dual_abi(self):
        runner = FakeRunner(missing_abi=True)
        self.assertIsNone(ArtifactSelector(runner).find("owner/repo", "HEAD",
                                                        ["arm64-v8a", "x86_64"]))

    def test_arm64_release_can_reuse_single_abi(self):
        runner = FakeRunner(missing_abi=True)
        self.assertEqual(ArtifactSelector(runner).find("owner/repo", "HEAD",
                                                       ["arm64-v8a"]), "21")

    def test_changed_source_must_recompile(self):
        runner = FakeRunner(changed_source=True)
        self.assertIsNone(ArtifactSelector(runner).find("owner/repo", "HEAD",
                                                        ["arm64-v8a"]))
        self.assertEqual(runner.called_api, 0)

    def test_changed_compiler_recipe_must_recompile(self):
        runner = FakeRunner(changed_recipe=True)
        self.assertIsNone(ArtifactSelector(runner).find("owner/repo", "HEAD",
                                                        ["arm64-v8a"]))

    def test_unrelated_history_cannot_reuse(self):
        runner = FakeRunner(ancestor=False)
        self.assertIsNone(ArtifactSelector(runner).find("owner/repo", "HEAD",
                                                        ["arm64-v8a"]))


if __name__ == "__main__":
    unittest.main()
