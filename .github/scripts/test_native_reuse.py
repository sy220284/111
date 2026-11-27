#!/usr/bin/env python3
"""Offline regression tests for safe native artifact reuse."""
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from select_native_artifacts import ArtifactSelector, SelectionError, native_recipe, configured_abis


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
                 expired_first=False, missing_abi=False, ancestor=True,
                 run_conclusion='success', failed_job=False,
                 changed_code_mode_adapter=False,
                 legacy_missing_adapter=False, changed_v8_builder=False, changed_v8_host_adapter=False,
                 changed_parent_cargo=False, changed_producer=False, changed_privacy=False, changed_delivery=False):
        self.run_conclusion = run_conclusion
        self.failed_job = failed_job
        self.failed_provider = False
        self.changed_source = changed_source
        self.changed_recipe = changed_recipe
        self.expired_first = expired_first
        self.missing_abi = missing_abi
        self.ancestor = ancestor
        self.changed_code_mode_adapter = changed_code_mode_adapter
        self.legacy_missing_adapter = legacy_missing_adapter
        self.changed_v8_builder = changed_v8_builder
        self.changed_v8_host_adapter = changed_v8_host_adapter
        self.changed_parent_cargo = changed_parent_cargo
        self.changed_producer = changed_producer
        self.changed_privacy = changed_privacy
        self.changed_delivery = changed_delivery
        self.git_queries = []
        self.called_api = 0
        self.last_workflow_query = []
        self.index_queries = []
        self.branch = "main"

    def __call__(self, args):
        if args and args[0] == "git":
            self.git_queries.append(args)
        if args[:2] == ["git", "rev-parse"]:
            ref = args[2]
            is_old = ref.startswith(("old:", "older:"))
            is_upstream = ref.endswith(":upstream/codex/codex-rs")
            if is_upstream and is_old and self.changed_source:
                return "old-tree"
            if is_upstream:
                return "same-tree"
            return "same-patcher"
        if args[:3] == ["git", "ls-tree", "-r"]:
            return "different producer" if self.changed_producer and args[3] in ("old","older") else "same producer"
        if args[:2] == ["git", "ls-tree"]:
            sha = args[2]
            if args[-1] in ("android-app/tools/prepare_android_remote_delivery.py", "android-app/tools/test_prepare_android_remote_delivery.py"):
                return "old delivery" if self.changed_delivery and sha.startswith(("old", "older")) else "same delivery"
            if args[-1] == "android-app/tools/prepare_android_realtime_privacy.py":
                return "old privacy" if self.changed_privacy and sha.startswith(("old","older")) else "same privacy" if self.changed_privacy else ""
            if args[-1] == "upstream/codex/.cargo":
                return ("old-parent-cargo" if sha.startswith(("old", "older")) and
                        self.changed_parent_cargo else "same-parent-cargo")
            if args[-1] == "android-app/tools/adapt_android_v8_build.py":
                return ("old-v8-host" if sha.startswith(("old", "older")) and
                        self.changed_v8_host_adapter else "same-v8-host")
            if args[-1] == "android-app/tools/prepare_android_v8.sh":
                return ("old-builder" if sha.startswith(("old", "older")) and
                        self.changed_v8_builder else "same-builder")
            if sha.startswith(("old", "older")) and self.legacy_missing_adapter:
                return ""
            if sha.startswith(("old", "older")) and self.changed_code_mode_adapter:
                return "old-adapter"
            return "same-adapter"
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
        if args[:2] == ["gh", "api"]:
            if "/actions/artifacts?name=" in args[2]:
                self.last_workflow_query = list(args)
                self.index_queries.append(args[-1])
                return json.dumps({"artifacts": [{"expired": False, "workflow_run": {"id": 21}}, {"expired": False, "workflow_run": {"id": 20}}]})
            if args[2].endswith("/actions/runs/21") or args[2].endswith("/actions/runs/20"):
                return json.dumps({"head_repository": {"full_name": "owner/repo"}, "path": ".github/workflows/ci.yml", "head_sha": "old" if args[2].endswith("/21") else "older", "head_branch": self.branch, "conclusion": self.run_conclusion})
            if args[2].endswith("/jobs?per_page=100"):
                return json.dumps({"jobs": [
                    {"name": f"编译 {abi} 核心",
                     "conclusion": "failure" if self.failed_job and abi == "arm64-v8a" else "success"}
                    for abi in ("arm64-v8a", "x86_64")
                ] + [{"name": "国内服务商目录与模型能力 Rust 回归测试", "conclusion": "failure" if self.failed_provider else "success"}]})
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
    def test_transient_api_error_retries_and_validates_json(self):
        from unittest.mock import patch
        from collections import deque
        results = deque([SelectionError("unexpected end of JSON input"), "{", '{"jobs":[]}'])
        def runner(args):
            value = results.popleft()
            if isinstance(value, Exception):
                raise value
            return value
        with patch("select_native_artifacts.time.sleep") as sleep:
            self.assertEqual(ArtifactSelector(runner).api_json("repos/a/b/actions/runs/1/jobs"), {"jobs": []})
            self.assertEqual(sleep.call_count, 2)

    def test_invalid_api_response_never_becomes_verified_receipt(self):
        from unittest.mock import patch
        with patch("select_native_artifacts.time.sleep"):
            with self.assertRaises(SelectionError):
                ArtifactSelector(lambda args: "[]").api_json("repos/a/b/actions/runs/1")

    def test_trigger_and_upload_changes_do_not_invalidate_binary(self):
        edited = COMPILER_JOB.replace("paths: [x]", "paths: [y]")
        edited = edited.replace("retention-days: 90", "retention-days: 30")
        self.assertEqual(native_recipe(COMPILER_JOB), native_recipe(edited))

    def test_new_binary_upload_name_keeps_historical_recipe_compatible(self):
        edited = COMPILER_JOB.replace("上传原生内核与构建清单", "上传原生内核二进制")
        self.assertEqual(native_recipe(COMPILER_JOB), native_recipe(edited))

    def test_checkout_transport_does_not_invalidate_binary(self):
        old = COMPILER_JOB.replace("      - name: 构建", "      - name: checkout\n        uses: actions/checkout@v4\n        with:\n          sparse-checkout: |\n            upstream/codex\n      - name: 构建")
        new = old.replace("          sparse-checkout: |", "          ref: ${{ github.event.pull_request.head.sha || github.sha }}\n          fetch-depth: 0\n          sparse-checkout: |\n            .github/scripts\n            .github/ci")
        self.assertEqual(native_recipe(old), native_recipe(new))

    def test_host_contract_gate_does_not_invalidate_binary(self):
        edited = COMPILER_JOB.replace(
            "needs: reuse", "needs: [reuse, provider-contract]"
        ).replace("if: needs.reuse.outputs.use != 'true'",
                  "if: needs.provider-contract.result == 'success'")
        self.assertEqual(native_recipe(COMPILER_JOB), native_recipe(edited))

    def test_compiler_change_invalidates_binary(self):
        edited = COMPILER_JOB.replace("cargo build --locked", "cargo build --release")
        self.assertNotEqual(native_recipe(COMPILER_JOB), native_recipe(edited))

    def test_arm64_signature_is_stable_when_x86_matrix_removed(self):
        full = """
jobs:
  native:
    runs-on: ubuntu-latest
    strategy:
      matrix:
        include:
          - rust_target: aarch64-linux-android
            abi: arm64-v8a
          - rust_target: x86_64-linux-android
            abi: x86_64
    env:
      RUST: 1.99.1
    steps:
      - name: build
        run: cargo build
      - name: 上传原生内核与构建清单
        uses: actions/upload-artifact@v4
"""
        arm_only = full.replace(
            "          - rust_target: x86_64-linux-android\\n            abi: x86_64\\n", ""
        )
        self.assertEqual(native_recipe(full, "arm64-v8a"),
                         native_recipe(arm_only, "arm64-v8a"))
        self.assertNotEqual(native_recipe(full, "x86_64"),
                            native_recipe(full, "arm64-v8a"))

    def test_dynamic_missing_abi_matrix_keeps_existing_arm_signature(self):
        prefix = "jobs:\n  native:\n    runs-on: ubuntu-latest\n    strategy:\n      matrix:\n"
        suffix = "\n    env:\n      RUST: 1.99.1\n    steps:\n      - name: build\n        run: cargo build\n      - name: 上传原生内核与构建清单\n"
        matrix = [
            {"rust_target": "aarch64-linux-android", "clang": "aarch64-linux-android29-clang",
             "clangxx": "aarch64-linux-android29-clang++", "abi": "arm64-v8a", "elf_arch": "AArch64"},
            {"rust_target": "x86_64-linux-android", "clang": "x86_64-linux-android29-clang",
             "clangxx": "x86_64-linux-android29-clang++", "abi": "x86_64", "elf_arch": "X86-64"},
        ]
        static = "        include:\n" + "\n".join(
            "\n".join(("          - " if index == 0 else "            ") + key + ": " + value
                      for index, (key, value) in enumerate(entry.items())) for entry in matrix
        )
        dynamic = "ABI_MATRIX_JSON: '" + json.dumps(matrix) + "'\n" + prefix + "        include: ${{ fromJSON(needs.reuse.outputs.matrix) }}" + suffix
        for abi in ("arm64-v8a", "x86_64"):
            self.assertEqual(native_recipe(prefix + static + suffix, abi), native_recipe(dynamic, abi))
        self.assertEqual(["arm64-v8a", "x86_64"], configured_abis(dynamic))
        with self.assertRaises(SelectionError):
            native_recipe(dynamic.replace("ABI_MATRIX_JSON:", "UNKNOWN:"), "arm64-v8a")
        edited = dynamic.replace("x86_64-linux-android29-clang++", "x86_64-linux-android30-clang++")
        self.assertEqual(native_recipe(dynamic, "arm64-v8a"), native_recipe(edited, "arm64-v8a"))
        self.assertNotEqual(native_recipe(dynamic, "x86_64"), native_recipe(edited, "x86_64"))

    def test_unrecognized_recipe_is_error(self):
        with self.assertRaises(SelectionError):
            native_recipe("jobs:\n  other:\n")

    def test_reuse_both_architectures(self):
        runner = FakeRunner()
        self.assertEqual(ArtifactSelector(runner).find("owner/repo", "HEAD",
                                                       ["arm64-v8a", "x86_64"]), "21")
        self.assertEqual(runner.called_api, 1)

    def test_feature_branch_is_scoped_to_its_own_native_build(self):
        runner = FakeRunner()
        runner.branch = "fix/android-code-mode-host-bundle-20261122"
        self.assertEqual(ArtifactSelector(runner).find("owner/repo", "HEAD",
            ["arm64-v8a", "x86_64"], branch=runner.branch), "21")
        self.assertIsNone(ArtifactSelector(runner).find("owner/repo", "HEAD", ["arm64-v8a"], branch="other"))

    def test_main_can_reuse_matching_ancestor_pr_artifact(self):
        runner = FakeRunner(missing_abi=True, run_conclusion="cancelled")
        self.assertEqual(ArtifactSelector(runner).find(
            "owner/repo", "HEAD", ["arm64-v8a"], branch="main"), "21")
        self.assertTrue(any("native-index-arm64-v8a-" in query for query in runner.index_queries))

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

    def test_cancelled_workflow_with_successful_arm64_job_is_reusable(self):
        runner = FakeRunner(missing_abi=True, run_conclusion="cancelled")
        self.assertEqual(ArtifactSelector(runner).find(
            "owner/repo", "HEAD", ["arm64-v8a"]), "21")

    def test_remote_delivery_adapter_invalidates_both_rust_proofs(self):
        runner = FakeRunner(changed_delivery=True)
        selector = ArtifactSelector(runner)
        self.assertNotEqual(selector.test_signature("HEAD"), selector.test_signature("old"))
        self.assertNotEqual(selector.signature("HEAD", "arm64-v8a"), selector.signature("old", "arm64-v8a"))

    def test_failed_provider_contract_cannot_reuse_artifact(self):
        runner = FakeRunner(missing_abi=True)
        runner.failed_provider = True
        self.assertIsNone(ArtifactSelector(runner).find("owner/repo", "HEAD", ["arm64-v8a"]))
        self.assertEqual(ArtifactSelector(runner).find("owner/repo", "HEAD", ["arm64-v8a"], require_provider=False), "21")

    def test_failed_native_job_cannot_reuse_artifact(self):
        runner = FakeRunner(missing_abi=True, failed_job=True)
        self.assertIsNone(ArtifactSelector(runner).find(
            "owner/repo", "HEAD", ["arm64-v8a"]))

    def test_upstream_docs_sdk_and_archived_ci_do_not_invalidate_binary(self):
        runner = FakeRunner()
        self.assertEqual(ArtifactSelector(runner).find(
            "owner/repo", "HEAD", ["arm64-v8a"]), "21")
        self.assertFalse(any(
            ref.endswith(":upstream/codex") or "CODEX_UPSTREAM_COMMIT" in ref
            for query in runner.git_queries for ref in query
        ))
        self.assertTrue(any(
            ":upstream/codex/codex-rs" in ref
            for query in runner.git_queries for ref in query
        ))

    def test_parent_cargo_config_change_requires_rebuild(self):
        runner = FakeRunner(changed_parent_cargo=True)
        self.assertIsNone(ArtifactSelector(runner).find(
            "owner/repo", "HEAD", ["arm64-v8a"]))
        self.assertEqual(runner.called_api, 0)

    def test_changed_source_must_recompile(self):
        runner = FakeRunner(changed_source=True)
        self.assertIsNone(ArtifactSelector(runner).find("owner/repo", "HEAD",
                                                        ["arm64-v8a"]))
        self.assertEqual(runner.called_api, 0)

    def test_changed_compiler_recipe_must_recompile(self):
        runner = FakeRunner(changed_recipe=True)
        self.assertIsNone(ArtifactSelector(runner).find("owner/repo", "HEAD",
                                                        ["arm64-v8a"]))

    def test_legacy_build_without_code_mode_adapter_must_recompile(self):
        runner = FakeRunner(legacy_missing_adapter=True)
        self.assertIsNone(ArtifactSelector(runner).find("owner/repo", "HEAD",
                                                        ["arm64-v8a"]))
        self.assertEqual(runner.called_api, 0)

    def test_changed_code_mode_adapter_must_recompile(self):
        runner = FakeRunner(changed_code_mode_adapter=True)
        self.assertIsNone(ArtifactSelector(runner).find("owner/repo", "HEAD",
                                                        ["arm64-v8a"]))
        self.assertEqual(runner.called_api, 0)

    def test_changed_v8_builder_must_recompile(self):
        runner = FakeRunner(changed_v8_builder=True)
        self.assertIsNone(ArtifactSelector(runner).find("owner/repo", "HEAD",
                                                        ["arm64-v8a"]))
        self.assertEqual(runner.called_api, 0)

    def test_changed_v8_host_adapter_must_recompile(self):
        runner = FakeRunner(changed_v8_host_adapter=True)
        self.assertIsNone(ArtifactSelector(runner).find("owner/repo", "HEAD",
                                                        ["arm64-v8a"]))
        self.assertEqual(runner.called_api, 0)

    def test_identical_indexed_sibling_producer_can_reuse(self):
        runner = FakeRunner(ancestor=False)
        self.assertEqual("21", ArtifactSelector(runner).find("owner/repo", "HEAD", ["arm64-v8a"]))

    def test_privacy_adapter_is_a_native_input(self):
        runner = FakeRunner(changed_privacy=True)
        self.assertIsNone(ArtifactSelector(runner).find("owner/repo", "HEAD", ["arm64-v8a"]))

    def test_unrelated_history_cannot_reuse(self):
        runner = FakeRunner(ancestor=False, changed_producer=True)
        self.assertIsNone(ArtifactSelector(runner).find("owner/repo", "HEAD",
                                                        ["arm64-v8a"]))


if __name__ == "__main__":
    unittest.main()
