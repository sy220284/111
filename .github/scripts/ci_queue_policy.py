#!/usr/bin/env python3
"""Cancel only CI belonging to the exact same obsolete or closed same-repo PR."""
import argparse
import json
import subprocess
from urllib.parse import quote

def gh(*args):
    return subprocess.check_output(["gh", *args], text=True)


def api(repo, path):
    return json.loads(gh("api", f"repos/{repo}/{path}"))


def pages(repo, path, field=None):
    separator = "&" if "?" in path else "?"
    page = 1
    while True:
        response = api(repo, f"{path}{separator}per_page=100&page={page}")
        rows = response[field] if field else response
        yield from rows
        if len(rows) < 100:
            return
        page += 1


def active_runs(repo, branch=None):
    # Unfiltered first page is insufficient when many checks have run.
    scope = "" if branch is None else "&branch=" + quote(branch, safe="")
    for status in ("queued", "in_progress", "waiting", "requested", "pending"):
        yield from pages(repo, f"actions/runs?status={status}{scope}", "workflow_runs")

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
            try:
                cancel(repo, rid)
            except subprocess.CalledProcessError:
                # Cancellation can race natural completion. Any still-active error is real.
                if request(repo, f"actions/runs/{rid}").get("status") != "completed":
                    raise
                continue
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
