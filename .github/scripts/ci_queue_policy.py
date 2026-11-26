#!/usr/bin/env python3
"""Cancel only superseded, idle PR acceptance; preserve current and running work."""
import argparse
import json
import re

WAITING = {"queued", "pending", "waiting", "requested"}
CI = ".github/workflows/ci.yml"


def own_run(repo, run):
    return (run.get("event") == "pull_request"
            and (run.get("head_repository") or {}).get("full_name") == repo
            and run.get("path", "").split("@", 1)[0] == CI)


def eligible(repo, run, pr, newer, jobs):
    head = pr["head"]
    if (pr.get("state") != "open" or pr["base"]["ref"] != "main"
        or (head.get("repo") or {}).get("full_name") != repo
        or not own_run(repo, run) or not own_run(repo, newer)
        or run.get("status") not in WAITING
        or run.get("head_branch") != head["ref"] or newer.get("head_branch") != head["ref"]
        or newer.get("head_sha") != head["sha"] or run.get("head_sha") == head["sha"]
        or newer["id"] <= run["id"]
        or newer.get("status") not in WAITING | {"in_progress", "completed"}
        or (newer.get("status") == "completed" and newer.get("conclusion") != "success")
        or {p["number"] for p in run.get("pull_requests", [])} != {pr["number"]}
        or {p["number"] for p in newer.get("pull_requests", [])} != {pr["number"]}):
        return False
    # An overall "queued" run can actually have active native or device jobs.
    # Completed evidence is left intact; a running or unknown job blocks cancellation.
    return all(j.get("status") in WAITING | {"completed"} for j in jobs)


def reconcile(repo, pr_number, api, pages, cancel, apply=False):
    pr = api(repo, f"pulls/{pr_number}")
    if pr.get("state") != "open" or (pr["head"].get("repo") or {}).get("full_name") != repo:
        return []
    from urllib.parse import quote
    branch = quote(pr["head"]["ref"], safe="")
    rows = list(pages(repo, f"actions/workflows/ci.yml/runs?branch={branch}&event=pull_request", "workflow_runs"))
    latest = [r for r in rows if own_run(repo, r) and r.get("head_sha") == pr["head"]["sha"]
              and {p["number"] for p in r.get("pull_requests", [])} == {pr_number}]
    if not latest:
        return []
    newer = max(latest, key=lambda r:r["id"])
    selected = []
    for old in rows:
        if not eligible(repo, old, pr, newer, []):
            continue
        # Never discard an independent or force-pushed history.
        old_sha, new_sha = old.get("head_sha",""), newer.get("head_sha","")
        if not all(re.fullmatch("[0-9a-f]{40}", s) for s in (old_sha,new_sha)):
            continue
        if api(repo,f"compare/{old_sha}...{new_sha}").get("status") != "ahead":
            continue
        current = api(repo,f"actions/runs/{old['id']}")
        candidate = api(repo,f"actions/runs/{newer['id']}")
        jobs = list(pages(repo,f"actions/runs/{old['id']}/jobs?filter=all","jobs"))
        live_pr = api(repo,f"pulls/{pr_number}")
        if not eligible(repo,current,live_pr,candidate,jobs):
            continue
        if apply:
            # Refresh the run after the job and head checks to narrow the
            # unavoidable REST cancellation race. No force-cancel is used.
            latest_run=api(repo,f"actions/runs/{old['id']}")
            if latest_run.get("status") not in WAITING or latest_run.get("run_attempt") != current.get("run_attempt"):
                continue
            cancel(repo,old["id"])
        selected.append(old["id"])
    return selected


def main():
    from retain_check_artifacts import api, pages, gh
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--repo",required=True)
    p.add_argument("--pr",type=int)
    p.add_argument("--apply",action="store_true")
    a=p.parse_args()
    prs=[a.pr] if a.pr else [x["number"] for x in pages(a.repo,"pulls?state=open")]
    def cancel(repo, rid):
        gh("api","--method","POST",f"repos/{repo}/actions/runs/{rid}/cancel")
    for number in prs:
        ids=reconcile(a.repo,number,api,pages,cancel,a.apply)
        print(json.dumps({"pr":number,"mode":"cancel_requested" if a.apply else "dry_run","runs":ids}))


if __name__ == "__main__":
    main()
