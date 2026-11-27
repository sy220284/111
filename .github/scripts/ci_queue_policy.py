#!/usr/bin/env python3
"""Reconcile obsolete PR CI, or explicitly switch the active CI queue to current main."""
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

def cutover(repo, sha, control_run, request=api, active=active_runs, cancel=None, apply=False):
    """Explicit main cutover; ordinary PR reconciliation retains its strict scope."""
    def assert_main():
        if request(repo, "git/ref/heads/main")["object"]["sha"] != sha:
            raise ValueError("切换目标已不是最新 main，停止清退")
    assert_main()
    control = request(repo, f"actions/runs/{control_run}")
    if (control.get("head_sha") != sha or control.get("head_branch") != "main"
            or control.get("event") not in {"push", "workflow_dispatch"}
            or control.get("path", "").split("@", 1)[0] != ".github/workflows/reconcile-ci-queue.yml"
            or (control.get("head_repository") or {}).get("full_name") != repo):
        raise ValueError("主线切换控制运行身份无效")
    cutoff = control["created_at"]
    def obsolete(run):
        return (run.get("id") != control_run
                and run.get("status") in UNFINISHED
                and run.get("path", "").split("@", 1)[0] == ".github/workflows/ci.yml"
                and (run.get("head_repository") or {}).get("full_name") == repo
                and run.get("head_sha") != sha
                and run.get("created_at", "") != ""
                and run["created_at"] < cutoff
                and run.get("event") in {"pull_request", "push", "workflow_dispatch"})
    selected = []
    seen = set()
    for run in active(repo):
        rid = run["id"]
        if rid in seen or not obsolete(run):
            continue
        seen.add(rid)
        # An explicitly requested cutover covers older same-repo acceptance
        # runs even when GitHub no longer includes their closed PR metadata.
        assert_main()
        if not obsolete(request(repo, f"actions/runs/{rid}")):
            continue
        if apply:
            try:
                cancel(repo, rid)
            except subprocess.CalledProcessError:
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
    p.add_argument("--cutover-sha")
    p.add_argument("--cutover-run", type=int)
    a = p.parse_args()
    def cancel(repo, rid):
        gh("api", "--method", "POST", f"repos/{repo}/actions/runs/{rid}/cancel")
    if a.cutover_sha or a.cutover_run:
        if not a.cutover_sha or not a.cutover_run or a.pr:
            p.error("主线切换需要完整目标提交及控制运行，且不能指定 PR")
        ids = cutover(a.repo, a.cutover_sha, a.cutover_run, cancel=cancel, apply=a.apply)
    else:
        ids = reconcile(a.repo, a.pr, cancel=cancel, apply=a.apply)
    print(f"{'已请求取消' if a.apply else '待取消'} {len(ids)} 个过期验收：{ids}", flush=True)

if __name__ == "__main__":
    main()
