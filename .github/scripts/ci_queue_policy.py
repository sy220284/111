#!/usr/bin/env python3
"""Cancel only CI belonging to the exact same obsolete or closed same-repo PR."""
import argparse
from retain_check_artifacts import active_runs, api, gh

UNFINISHED = {"queued", "pending", "waiting", "requested", "in_progress"}

def eligible(repo, run, pr):
    head = pr["head"]
    return ((head.get("repo") or {}).get("full_name") == repo
            and run.get("event") == "pull_request"
            and (run.get("head_repository") or {}).get("full_name") == repo
            and run.get("path", "").split("@", 1)[0] == ".github/workflows/ci.yml"
            and run.get("status") in UNFINISHED
            and run.get("head_branch") == head["ref"]
            and {p["number"] for p in run.get("pull_requests", [])} == {pr["number"]}
            and (pr["state"] == "closed" or
                 pr["state"] == "open" and run.get("head_sha") != head["sha"]))

def reconcile(repo, number, request=api, active=active_runs, cancel=None, apply=False):
    pr = request(repo, f"pulls/{number}") if number else None
    if pr and (pr["head"].get("repo") or {}).get("full_name") != repo:
        return []
    selected = []
    seen = set()
    for run in active(repo, pr["head"]["ref"] if pr else None):
        rid = run["id"]
        if rid in seen:
            continue
        seen.add(rid)
        if run.get("event") != "pull_request" or run.get("path", "").split("@", 1)[0] != ".github/workflows/ci.yml":
            continue
        prs = run.get("pull_requests", [])
        if len(prs) != 1 or number and prs[0]["number"] != number:
            continue
        n = prs[0]["number"]
        candidate = request(repo, f"pulls/{n}")
        if not eligible(repo, run, candidate):
            continue
        live = request(repo, f"actions/runs/{rid}")
        candidate = request(repo, f"pulls/{n}")
        if not eligible(repo, live, candidate):
            continue
        if apply:
            cancel(repo, rid)
        selected.append(rid)
    return selected

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--repo", required=True)
    p.add_argument("--pr", type=int)
    p.add_argument("--apply", action="store_true")
    a = p.parse_args()
    def cancel(repo, rid):
        gh("api", "--method", "POST", f"repos/{repo}/actions/runs/{rid}/cancel")
    ids = reconcile(a.repo, a.pr, cancel=cancel, apply=a.apply)
    print(f"{'已请求取消' if a.apply else '待取消'} {len(ids)} 个过期验收：{ids}", flush=True)

if __name__ == "__main__":
    main()
