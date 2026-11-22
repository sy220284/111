#!/usr/bin/env python3
"""Select a previously verified Android native build with identical binary inputs.

Never trust just CODEX_UPSTREAM_COMMIT or the newest successful run. Reuse is
allowed only for an ancestor of the current commit, an identical source tree
and compiler recipe, and all requested unexpired artifacts from that run.
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


def native_recipe(workflow):
    """Hash the compilation steps, excluding trigger/gating/upload policy."""
    marker = "\n  native:\n"
    if marker not in workflow:
        raise SelectionError("原生构建工作流缺少 native 作业")
    job = workflow.split(marker, 1)[1]
    start = re.search(r"(?m)^    runs-on:", job)
    end = re.search(r"(?m)^      - name: 上传原生内核与构建清单\s*$", job)
    if not start or not end or start.start() >= end.start():
        raise SelectionError("无法识别原生编译配置边界；需核对后再复用")
    return job[start.start():end.start()].strip()


class ArtifactSelector:
    def __init__(self, runner=command):
        self.run = runner

    def signature(self, sha):
        # A tree object's SHA changes if ANY file in the bundled upstream changes.
        upstream = self.run(["git", "rev-parse", f"{sha}:upstream/codex"])
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
        version = self.run(["git", "show", f"{sha}:CODEX_UPSTREAM_COMMIT"]).splitlines()[0]
        workflow = self.run(["git", "show", f"{sha}:.github/workflows/build-native-codex.yml"])
        return (upstream, patcher, code_mode_adapter, v8_builder, v8_host_adapter, git_builder, version, native_recipe(workflow))

    def is_ancestor(self, old, new):
        # Not an error: candidates from a previous main history can be skipped.
        return self.run(["git", "merge-base", old, new]) == old

    def find(self, repository, target, abis):
        current_signature = self.signature(target)
        runs = json.loads(self.run([
            "gh", "run", "list", "--repo", repository,
            "--workflow", "build-native-codex.yml", "--branch", "main",
            "--limit", "200", "--json", "databaseId,headSha,conclusion",
        ]))
        expected = {f"codex-android-native-{abi}" for abi in abis}
        for item in runs:
            if item.get("conclusion") != "success":
                continue
            old = item.get("headSha")
            if not old or not self.is_ancestor(old, target):
                continue
            if self.signature(old) != current_signature:
                continue
            run_id = item["databaseId"]
            response = json.loads(self.run([
                "gh", "api",
                f"repos/{repository}/actions/runs/{run_id}/artifacts?per_page=100",
            ]))
            available = {
                artifact["name"]
                for artifact in response.get("artifacts", [])
                if not artifact.get("expired", True)
                and artifact.get("size_in_bytes", 0) > 0
            }
            if expected <= available:
                return str(run_id)
        return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--abis", nargs="+", required=True,
                        choices=("arm64-v8a", "x86_64"))
    parser.add_argument("--target", default="HEAD")
    args = parser.parse_args()
    try:
        selector = ArtifactSelector()
        run_id = selector.find(args.repo, args.target, args.abis)
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
