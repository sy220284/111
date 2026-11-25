#!/usr/bin/env python3
"""Choose a fully verified main revision without invalidating it for repository maintenance."""
import argparse
import subprocess
import sys

from decide_android_build import changed_paths
from verify_release_acceptance import REQUIRED_JOBS, find_acceptance

from ci_policy import maintenance_only


def eligible(sha: str, latest: str) -> bool:
    # A non-ancestor or a git failure must never be treated as a safe delta.
    ancestry = subprocess.run(
        ["git", "merge-base", "--is-ancestor", sha, latest], check=False
    )
    if ancestry.returncode == 1:
        raise ValueError(f"发布候选 {sha} 不属于 main 历史")
    ancestry.check_returncode()
    return maintenance_only(changed_paths(sha, latest))


def first_parent_revisions(latest: str) -> list[str]:
    result = subprocess.run(
        ["git", "rev-list", "--first-parent", latest],
        text=True, capture_output=True, check=True,
    )
    return result.stdout.splitlines()


def select(repo: str, latest: str, trigger: str | None = None) -> str | None:
    revisions = [trigger] if trigger else first_parent_revisions(latest)
    for sha in revisions:
        if not eligible(sha, latest):
            # For manual releases, do not cross an unverified client or CI change.
            return None
        if all(find_acceptance(repo, sha, workflow) is not None for workflow in REQUIRED_JOBS):
            return sha
    return None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--latest-main", required=True)
    parser.add_argument("--trigger-sha")
    parser.add_argument("--check-only", action="store_true")
    args = parser.parse_args()
    try:
        if args.check_only:
            if not args.trigger_sha:
                raise ValueError("--check-only 需要指定 --trigger-sha")
            return 0 if eligible(args.trigger_sha, args.latest_main) else 3
        sha = select(args.repo, args.latest_main, args.trigger_sha)
        if sha is None:
            print("未找到同一 SHA 全部通过且未被有效变更替代的 main 候选", file=sys.stderr)
            return 3
        print(sha)
        return 0
    except (subprocess.CalledProcessError, OSError, ValueError, KeyError, TypeError) as exc:
        print(f"发布候选核验失败：{exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
