#!/usr/bin/env python3
"""Select a previously verified Android native build with identical binary inputs.

Ignore upstream documentation and unrelated platforms. Reuse only when the
Android Rust source tree, inherited Cargo configuration, native build recipe,
and platform adapters match a verified artifact. Sibling commits additionally
require identical trusted producer workflow and index/selector code.
"""
import argparse
import json
import re
import subprocess
import sys
import time


class SelectionError(RuntimeError):
    pass


def command(args):
    completed = subprocess.run(args, text=True, capture_output=True)
    if completed.returncode:
        raise SelectionError(
            f"命令失败 ({completed.returncode}): {' '.join(args)}\n{completed.stderr.strip()}"
        )
    return completed.stdout.strip()


def configured_abis(workflow):
    """The canonical matrix is shared with the missing-ABI build planner."""
    match = re.search(r"(?m)^\s*ABI_MATRIX_JSON: '([^'\n]+)'$", workflow)
    if match:
        return [entry["abi"] for entry in configured_matrix(workflow)]
    # Historical static matrix is only used to validate existing artifacts.
    return re.findall(r"(?m)^\s*abi: (arm64-v8a|x86_64)$", workflow)


def configured_matrix(workflow):
    match = re.search(r"(?m)^\s*ABI_MATRIX_JSON: '([^'\n]+)'$", workflow)
    if not match:
        raise SelectionError("缺少原生 ABI 唯一编译矩阵")
    try:
        matrix = json.loads(match.group(1))
        keys = {"rust_target", "clang", "clangxx", "abi", "elf_arch"}
        if not isinstance(matrix, list) or not matrix:
            raise ValueError("矩阵为空")
        for entry in matrix:
            if set(entry) != keys or any(not isinstance(x, str) or not re.fullmatch(r"[A-Za-z0-9_+\-]+", x) for x in entry.values()):
                raise ValueError("ABI 编译条目无效")
        if {e["abi"] for e in matrix} != {"arm64-v8a", "x86_64"} or len(matrix) != 2:
            raise ValueError("ABI 必须精确覆盖手机及测试核心")
        return matrix
    except (TypeError, ValueError, KeyError) as exc:
        raise SelectionError(f"原生 ABI 矩阵无效：{exc}") from exc


def native_recipe(workflow, abi=None):
    """Hash the compilation steps, excluding trigger/gating/upload policy."""
    marker = "\n  native:\n"
    if marker not in workflow:
        raise SelectionError("原生构建工作流缺少 native 作业")
    job = workflow.split(marker, 1)[1]
    start = re.search(r"(?m)^    runs-on:", job)
    end = re.search(r"(?m)^      - name: 上传原生内核(?:与构建清单|二进制)\s*$", job)
    if not start or not end or start.start() >= end.start():
        raise SelectionError("无法识别原生编译配置边界；需核对后再复用")
    recipe = job[start.start():end.start()].strip()
    recipe = re.sub(r'(?m)^    timeout-minutes: \d+\n', '', recipe)
    # Checkout transport and CI-index-only paths do not alter native inputs.
    recipe = re.sub(r"(?m)^          (?:ref: .*github\.event\.pull_request\.head\.sha.*|fetch-depth: 0)\n|^            \.github/(?:scripts|ci)\n", "", recipe)
    dynamic = "        include: ${{ fromJSON(needs.reuse.outputs.matrix) }}"
    if dynamic in recipe:
        keys = ("rust_target", "clang", "clangxx", "abi", "elf_arch")
        entries = []
        for entry in configured_matrix(workflow):
            entries.append("          - rust_target: " + entry[keys[0]])
            entries.extend("            " + key + ": " + entry[key] for key in keys[1:])
        recipe = recipe.replace(dynamic, "        include:\n" + "\n".join(entries))
    # Hash only the requested ABI's matrix entry; dropping an unrelated ABI
    # does not alter the already validated ARM64 compiler invocation.
    if abi is None or "        include:\n" not in recipe:
        return recipe
    prefix, marker, remainder = recipe.partition("        include:\n")
    entries, env_marker, suffix = remainder.partition("\n    env:")
    if not env_marker:
        raise SelectionError("原生编译配置缺少矩阵环境变量边界")
    blocks = re.split(r"(?=^          - rust_target: )", entries, flags=re.MULTILINE)
    if blocks[0].strip():
        raise SelectionError("原生编译矩阵存在无法识别的条目")
    found = [block for block in blocks[1:] if re.search(
        rf"(?m)^            abi: {re.escape(abi)}$", block
    )]
    if len(found) != 1:
        raise SelectionError(f"原生编译矩阵缺少唯一的 {abi} 架构")
    return prefix + marker + found[0].rstrip("\n") + env_marker + suffix


def provider_recipe(workflow):
    marker = "\n  provider-contract:\n"
    if marker not in workflow:
        return ""
    job = workflow.split(marker, 1)[1]
    job = re.split(r"(?m)^  [a-z][a-z0-9-]*:\s*$", job, maxsplit=1)[0]
    steps = job[job.index("    runs-on:"):].split("\n      - name: 生成 Rust 回归凭证", 1)[0]
    steps = re.sub(r'(?m)^    timeout-minutes: \d+\n', '', steps)
    return re.sub(r"(?m)^          (?:ref: .*github\.event\.pull_request\.head\.sha.*|fetch-depth: 0)\n|^            \.github/(?:scripts|ci)\n", "", steps).strip()


class ArtifactSelector:
    def __init__(self, runner=command):
        self.run = runner

    def api_json(self, endpoint):
        """Retry only read-only GitHub API requests; never accept an unverified receipt."""
        for attempt in range(3):
            try:
                response = json.loads(self.run(["gh", "api", endpoint]))
                if not isinstance(response, dict):
                    raise SelectionError("GitHub API 返回非对象响应")
                return response
            except (SelectionError, json.JSONDecodeError) as exc:
                if attempt == 2:
                    raise SelectionError(f"GitHub API 三次读取仍失败：{endpoint}: {exc}") from exc
                print(f"GitHub API 读取失败，重新核验 ({attempt + 1}/3): {endpoint}", file=sys.stderr)
                time.sleep(attempt + 1)

    def signature(self, sha, abi='arm64-v8a'):
        # Changes to upstream SDKs, docs or archived CI do not alter Android ELF files.
        upstream = self.run(["git", "rev-parse", f"{sha}:upstream/codex/codex-rs"])
        # Cargo reads parent .cargo configuration from the upstream root.
        parent_cargo = self.run(["git", "ls-tree", sha, "--", "upstream/codex/.cargo"])
        patcher = self.run(["git", "rev-parse", f"{sha}:android-app/tools/prepare_android_mxc.py"])
        # Older valid builds predate the Android adapter. ls-tree returns an
        # empty match for those commits; the differing signature forces rebuild.
        code_mode_adapter = self.run([
            "git", "ls-tree", sha, "--", "android-app/tools/prepare_android_code_mode.py"
        ])
        v8_builder = self.run([
            "git", "ls-tree", sha, "--", "android-app/tools/prepare_android_v8.sh"
        ])
        v8_host_adapter = self.run([
            "git", "ls-tree", sha, "--", "android-app/tools/adapt_android_v8_build.py"
        ])
        git_builder = self.run([
            "git", "ls-tree", sha, "--", "android-app/tools/build_android_git.sh"
        ])
        workflow = self.run(["git", "show", f"{sha}:.github/workflows/build-native-codex.yml"])
        signature = (upstream, parent_cargo, patcher, code_mode_adapter, v8_builder,
                     v8_host_adapter, git_builder, native_recipe(workflow, abi))
        privacy = self.run(["git", "ls-tree", sha, "--", "android-app/tools/prepare_android_realtime_privacy.py"])
        if privacy:
            signature += (privacy,)
        remote_delivery = self.run(["git", "ls-tree", sha, "--", "android-app/tools/prepare_android_remote_delivery.py"])
        if remote_delivery:
            signature += (remote_delivery,)
        if "\n  v8:\n" in workflow:
            from v8_artifacts import producer_recipe
            signature += (producer_recipe(workflow),
                          self.run(["git","ls-tree",sha,"--","android-app/tools/v8_toolchain_provenance.py","tools/dev/toolchain-lock.json","tools/dev/auxiliary.py","tools/dev/component-bundle.py","tools/dev/host_components.py","upstream/codex/codex-rs/Cargo.lock"]))
        return signature

    def test_signature(self, sha):
        upstream = self.run(["git", "rev-parse", f"{sha}:upstream/codex/codex-rs"])
        parent_cargo = self.run(["git", "ls-tree", sha, "--", "upstream/codex/.cargo"])
        workflow = self.run(["git", "show", f"{sha}:.github/workflows/build-native-codex.yml"])
        # The workstation executor is produced by the same verified host job.
        # Its adapter and tests are part of the proof, even when the upstream tree is unchanged.
        delivery = self.run(["git", "ls-tree", sha, "--",
                             "android-app/tools/prepare_android_remote_delivery.py",
                             "android-app/tools/test_prepare_android_remote_delivery.py"])
        return (upstream, parent_cargo, provider_recipe(workflow), delivery)

    def is_ancestor(self, old, new):
        try:
            return self.run(["git", "merge-base", old, new]) == old
        except SelectionError as exc:
            print(f"跳过不可确认祖先关系的历史候选 {old}: {exc}", file=sys.stderr)
            return False

    def index_runs(self, repository, name):
        matches = set()
        page = 1
        while True:
            response = self.api_json(f"repos/{repository}/actions/artifacts?name={name}&per_page=100&page={page}")
            artifacts = response.get("artifacts", [])
            matches.update(a["workflow_run"]["id"] for a in artifacts if not a.get("expired", True) and a.get("workflow_run"))
            if len(artifacts) < 100:
                break
            page += 1
        return matches

    def indexed_candidates(self, repository, signatures):
        from native_index import fingerprint
        groups = [self.index_runs(repository, f"native-index-{abi}-{fingerprint(signature)}")
                  for abi, signature in signatures.items()]
        return sorted(set.intersection(*groups), reverse=True) if groups else []

    @staticmethod
    def historical(repository):
        from pathlib import Path
        path = Path(__file__).resolve().parents[1] / "ci" / "native-migration.json"
        return {item["run_id"]: item for item in json.loads(path.read_text(encoding="utf-8"))
                if item["repository"] == repository}

    def same_producer(self, old, target):
        # Exact producer/caller and index code, not just a similar job name.
        paths = (".github/workflows/ci.yml", ".github/workflows/build-native-codex.yml",
                 ".github/scripts/native_index.py", ".github/scripts/select_native_artifacts.py")
        try:
            previous = self.run(["git", "ls-tree", "-r", old, "--", *paths])
            current = self.run(["git", "ls-tree", "-r", target, "--", *paths])
        except SelectionError:
            return False
        return bool(previous) and previous == current

    def source(self, repository, run_id, target, branch, *, indexed=False):
        run = self.api_json(f"repos/{repository}/actions/runs/{run_id}")
        if (run.get("head_repository") or {}).get("full_name") != repository:
            return None
        if run.get("path", "").split("@", 1)[0] not in {
                ".github/workflows/ci.yml", ".github/workflows/build-native-codex.yml"}:
            return None
        if branch != "main" and run.get("head_branch") != branch:
            return None
        old = run.get("head_sha")
        if old and (self.is_ancestor(old, target) or
                    (indexed and self.same_producer(old, target))):
            return old
        return None

    def jobs(self, repository, run_id):
        jobs = self.api_json(f"repos/{repository}/actions/runs/{run_id}/jobs?per_page=100")
        return {job.get("name", "").rsplit(" / ", 1)[-1]
                for job in jobs.get("jobs", []) if job.get("conclusion") == "success"}

    def legacy_source(self, record, old):
        return (old == record["head_sha"] and
                self.run(["git", "rev-parse", f"{old}^{{tree}}"]) == record["source_tree"])

    def find_provider(self, repository, target, branch="main"):
        from native_index import fingerprint
        signature = self.test_signature(target)
        history = self.historical(repository)
        ids = list(dict.fromkeys(sorted(self.index_runs(repository, "provider-index-" + fingerprint(signature)), reverse=True) + list(history)))
        for run_id in ids:
            old = self.source(repository, run_id, target, branch, indexed=run_id not in history)
            if not old:
                continue
            if run_id in history and not self.legacy_source(history[run_id], old):
                continue
            try:
                if self.test_signature(old) != signature:
                    continue
            except SelectionError as exc:
                print(f"跳过无法核对 Rust 测试指纹的历史候选 {old}: {exc}", file=sys.stderr)
                continue
            if "国内服务商目录与模型能力 Rust 回归测试" in self.jobs(repository, run_id):
                return str(run_id)
        return None

    def find(self, repository, target, abis, branch="main", require_provider=True):
        current_signatures = {abi: self.signature(target, abi) for abi in abis}
        history = {rid: item for rid, item in self.historical(repository).items()
                   if set(abis) == {item["abi"]}}
        ids = list(dict.fromkeys(self.indexed_candidates(repository, current_signatures) + list(history)))
        expected = {f"codex-android-native-{abi}" for abi in abis}
        for run_id in ids:
            old = self.source(repository, run_id, target, branch, indexed=run_id not in history)
            if not old:
                continue
            try:
                if any(self.signature(old, abi) != current_signatures[abi] for abi in abis):
                    continue
            except SelectionError as exc:
                print(f"跳过无法核对原生编译指纹的历史候选 {old}: {exc}", file=sys.stderr)
                continue
            response = self.api_json(f"repos/{repository}/actions/runs/{run_id}/artifacts?per_page=100")
            available = {a["name"]: a for a in response.get("artifacts", [])
                         if not a.get("expired", True) and a.get("size_in_bytes", 0) > 0}
            if not expected <= available.keys():
                continue
            if run_id in history:
                record = history[run_id]
                asset = available[f"codex-android-native-{record['abi']}"]
                if (not self.legacy_source(record, old) or asset.get("id") != record["artifact_id"]
                        or asset.get("digest") != record["artifact_digest"]):
                    continue
            if {f"编译 {abi} 核心" for abi in abis} <= self.jobs(repository, run_id):
                if require_provider and not self.find_provider(repository, target, branch):
                    return None
                return str(run_id)
        return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--abis", nargs="+",
                        choices=("arm64-v8a", "x86_64"))
    parser.add_argument("--provider-only", action="store_true")
    parser.add_argument("--compiler-only", action="store_true")
    parser.add_argument("--target", default="HEAD")
    parser.add_argument("--branch", default="main")
    args = parser.parse_args()
    if args.provider_only == bool(args.abis) or (args.provider_only and args.compiler_only):
        parser.error("指定 --provider-only 或 --abis；两者互斥")
    try:
        selector = ArtifactSelector()
        run_id = (selector.find_provider(args.repo, args.target, args.branch) if args.provider_only
                  else selector.find(args.repo, args.target, args.abis, branch=args.branch,
                                     require_provider=not args.compiler_only))
        if run_id:
            print(run_id)
            print(f"复用已核验的原生构建或 Rust 回归凭证 {run_id}",
                  file=sys.stderr)
            return 0
        print("未找到与当前输入一致的原生构建或 Rust 回归凭证，需要补建或补测",
              file=sys.stderr)
        return 3
    except (SelectionError, KeyError, ValueError, json.JSONDecodeError) as error:
        print(f"原生内核复用检查失败：{error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
