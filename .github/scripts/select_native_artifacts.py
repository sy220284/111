#!/usr/bin/env python3
"""Select a previously verified Android native build with identical binary inputs.

Ignore upstream documentation and unrelated platforms. Reuse only when the
Android Rust source tree, inherited Cargo configuration, native build recipe,
and platform adapters match an ancestor with a verified, unexpired artifact.
"""
import argparse
import json
import re
import subprocess
import sys


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
    end = re.search(r"(?m)^      - name: 上传原生内核与构建清单\s*$", job)
    if not start or not end or start.start() >= end.start():
        raise SelectionError("无法识别原生编译配置边界；需核对后再复用")
    recipe = job[start.start():end.start()].strip()
    # Checkout transport and CI-index-only paths do not alter native inputs.
    recipe = re.sub(r"(?m)^          (?:ref: .*github\\.event\\.pull_request\\.head\\.sha.*|fetch-depth: 0|\\.github/scripts|\\.github/ci)\\n", "", recipe)
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


class ArtifactSelector:
    def __init__(self, runner=command):
        self.run = runner

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
        return (upstream, parent_cargo, patcher, code_mode_adapter, v8_builder, v8_host_adapter, git_builder, native_recipe(workflow, abi))

    def is_ancestor(self, old, new):
        # Not an error: candidates from a previous main history can be skipped.
        return self.run(["git", "merge-base", old, new]) == old

    def indexed_candidates(self, repository, signatures):
        from native_index import fingerprint
        groups = []
        for abi, signature in signatures.items():
            name = f"native-index-{abi}-{fingerprint(signature)}"
            matches = set()
            page = 1
            while True:
                response = json.loads(self.run(["gh", "api", f"repos/{repository}/actions/artifacts?name={name}&per_page=100&page={page}"]))
                artifacts = response.get("artifacts", [])
                matches.update(a["workflow_run"]["id"] for a in artifacts if not a.get("expired", True) and a.get("workflow_run"))
                if len(artifacts) < 100:
                    break
                page += 1
            groups.append(matches)
        return sorted(set.intersection(*groups), reverse=True) if groups else []

    def find(self, repository, target, abis, branch="main"):
        current_signatures = {abi: self.signature(target, abi) for abi in abis}
        from pathlib import Path
        legacy_file = Path(__file__).resolve().parents[1] / "ci" / "native-migration.json"
        legacy = json.loads(legacy_file.read_text(encoding="utf-8"))
        # The migration record is checked against the actual compiler checkout,
        # full Git tree, successful regression job and immutable artifact digest.
        historical = {item["run_id"]: item for item in legacy
                      if item["repository"] == repository and set(abis) == {item["abi"]}}
        ids = list(dict.fromkeys(self.indexed_candidates(repository, current_signatures) + list(historical)))
        expected = {f"codex-android-native-{abi}" for abi in abis}
        for run_id in ids:
            run = json.loads(self.run(["gh", "api", f"repos/{repository}/actions/runs/{run_id}"]))
            if (run.get("head_repository") or {}).get("full_name") != repository:
                continue
            if branch != "main" and run.get("head_branch") != branch:
                continue
            old = run.get("head_sha")
            if not old or not self.is_ancestor(old, target):
                continue
            try:
                if any(self.signature(old, abi) != current_signatures[abi] for abi in abis):
                    continue
            except SelectionError:
                continue
            response = json.loads(self.run([
                "gh", "api", f"repos/{repository}/actions/runs/{run_id}/artifacts?per_page=100",
            ]))
            available = {a["name"]: a for a in response.get("artifacts", [])
                         if not a.get("expired", True) and a.get("size_in_bytes", 0) > 0}
            if not expected <= available.keys():
                continue
            if run_id in historical:
                record = historical[run_id]
                asset = available[f"codex-android-native-{record['abi']}"]
                if (old != record["head_sha"] or asset.get("id") != record["artifact_id"]
                        or asset.get("digest") != record["artifact_digest"]):
                    continue
                actual_tree = self.run(["git", "rev-parse", f"{old}^{{tree}}"])
                if actual_tree != record["source_tree"]:
                    continue
            jobs = json.loads(self.run([
                "gh", "api", f"repos/{repository}/actions/runs/{run_id}/jobs?per_page=100"
            ]))
            succeeded = {job.get("name", "").rsplit(" / ", 1)[-1]
                         for job in jobs.get("jobs", []) if job.get("conclusion") == "success"}
            if ({f"编译 {abi} 核心" for abi in abis} |
                    {"国内服务商目录与模型能力 Rust 回归测试"}) <= succeeded:
                return str(run_id)
        return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--abis", nargs="+", required=True,
                        choices=("arm64-v8a", "x86_64"))
    parser.add_argument("--target", default="HEAD")
    parser.add_argument("--branch", default="main")
    args = parser.parse_args()
    try:
        selector = ArtifactSelector()
        run_id = selector.find(args.repo, args.target, args.abis, branch=args.branch)
        if run_id:
            print(run_id)
            print(f"复用原生核心构建 {run_id}（同源码、编译配置、有效产物：{', '.join(args.abis)}）",
                  file=sys.stderr)
            return 0
        print("没有与当前源码和编译配置完全一致的可用原生内核产物；需要新的原生编译",
              file=sys.stderr)
        return 3
    except (SelectionError, KeyError, ValueError, json.JSONDecodeError) as error:
        print(f"原生内核复用检查失败：{error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
