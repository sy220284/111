#!/usr/bin/env python3
"""Clean merged branches and inaccessible Actions caches; preserve source history."""
import argparse
import re
import subprocess
import os
from datetime import datetime, timezone, timedelta
from retain_check_artifacts import api, pages, gh

KEEP = {"main", "master", "develop", "audit/dsh-go-ui-journey-20261123"}


def protected_refs(prs, active):
    return {p["head"]["ref"] for p in prs} | {p["base"]["ref"] for p in prs} | {
        r["head_branch"] for r in active}


def merged_candidates(repo, branches, closed, blocked):
    merged = {(p["head"]["ref"], p["head"]["sha"]) for p in closed
              if p.get("merged_at") and p["base"]["ref"] == "main"
              and (p["head"].get("repo") or {}).get("full_name") == repo}
    return [b for b in branches if b["name"] not in KEEP | blocked
            and not b.get("protected") and not b["name"].startswith(("release/", "archive/"))
            and (b["name"], b["commit"]["sha"]) in merged]


def cache_candidates(caches, branches, closed, active, now):
    closed_ids = {p["number"] for p in closed}
    names = {b["name"] for b in branches}
    blocked = {r["head_branch"] for r in active}
    result = []
    for c in caches:
        ref = c["ref"]
        accessed = datetime.fromisoformat(c["last_accessed_at"].replace("Z", "+00:00"))
        if now - accessed < timedelta(hours=24):
            continue
        match = re.fullmatch(r"refs/pull/(\d+)/merge", ref)
        if match and int(match[1]) in closed_ids and not active:
            result.append(c)
        elif ref.startswith("refs/heads/") and ref[11:] not in names | blocked | KEEP:
            result.append(c)
    return result


def snapshot(repo):
    prs = list(pages(repo, "pulls?state=open"))
    closed = list(pages(repo, "pulls?state=closed"))
    branches = list(pages(repo, "branches"))
    active = [r for status in ("queued", "in_progress", "waiting", "requested", "pending")
              for r in pages(repo, "actions/runs?status=" + status, "workflow_runs")
              if r["id"] != int(os.environ.get("GITHUB_RUN_ID", "0"))]
    return prs, closed, branches, active


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--repo", required=True)
    p.add_argument("--apply", action="store_true")
    args = p.parse_args()
    repo = args.repo
    prs, closed, branches, active = snapshot(repo)
    candidates = merged_candidates(repo, branches, closed, protected_refs(prs, active))
    removed = 0
    deferred = 0
    # A branch can be removed only if its exact tip is reachable from main.
    # Squash-only or otherwise unproven histories remain available for native reuse.
    head = api(repo, "branches/main")["commit"]["sha"]
    for b in candidates:
        name, sha = b["name"], b["commit"]["sha"]
        check = subprocess.run(["git", "merge-base", "--is-ancestor", sha, head])
        if check.returncode == 1:
            print(f"保留独立历史：{name}")
            deferred += 1
            continue
        check.check_returncode()
        print(f"{'删除' if args.apply else '预览'}已合并分支：{name}")
        if args.apply:
            prs_now = list(pages(repo, "pulls?state=open"))
            if name in protected_refs(prs_now, []):
                continue
            # Compare-and-delete prevents erasing commits pushed after the snapshot.
            ref = "refs/heads/" + name
            subprocess.run(["git", "push", f"--force-with-lease={ref}:{sha}",
                            "origin", ":" + ref], check=True)
            removed += 1
    prs, closed, branches, active = snapshot(repo)
    caches = list(pages(repo, "actions/caches", "actions_caches"))
    candidates = cache_candidates(caches, branches, closed, active, datetime.now(timezone.utc))
    cache_count = cache_bytes = 0
    for c in candidates:
        print(f"{'删除' if args.apply else '预览'}失效缓存：{c['id']} ({c['size_in_bytes']} bytes)")
        if args.apply:
            gh("api", "--method", "DELETE", f"repos/{repo}/actions/caches/{c['id']}")
            cache_count += 1
            cache_bytes += c["size_in_bytes"]
    summary = (f"已删除分支 {removed}；保留独立历史 {deferred}；"
               f"失效缓存候选 {len(candidates)}，删除 {cache_count}，释放 {cache_bytes} 字节。"
               "正式 Release、标签及有效缓存保持原样。")
    print(summary)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as f:
            f.write("## 仓库管家\n\n" + summary + "\n")


if __name__ == "__main__":
    main()
