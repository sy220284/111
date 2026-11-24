#!/usr/bin/env python3
"""Retain current Actions evidence without touching releases or run history."""
import argparse
import json
import re
import subprocess
from collections import defaultdict

from select_native_artifacts import ArtifactSelector

CHECK_WORKFLOWS = {
    ".github/workflows/build-android.yml",
    ".github/workflows/build-native-codex.yml",
    ".github/workflows/check-android-ui.yml",
    ".github/workflows/dsh-go-reference-audit.yml",
    ".github/workflows/rehearse-android-upgrade.yml",
    ".github/workflows/import-codex.yml",
}
NATIVE = re.compile(r"^codex-android-native-(?:arm64-v8a|x86_64)$")
DSH = re.compile(r"^(dsh-go-)v[0-9.]+(-android-reference|-ui-evidence)$")
CONSUMERS = CHECK_WORKFLOWS | {".github/workflows/release.yml"}


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


def workflow_path(run):
    return run["path"].split("@", 1)[0]


def family(name):
    return DSH.sub(r"\1current\2", name)


def plan(artifacts, runs, open_heads, native_keep, native_safe):
    """Select only superseded completed artifacts, independent of API ordering."""
    groups = defaultdict(list)
    for a in artifacts:
        run = runs[a["workflow_run"]["id"]]
        path = workflow_path(run)
        if a["expired"] or path not in CHECK_WORKFLOWS or run["status"] != "completed":
            continue
        if NATIVE.fullmatch(a["name"]):
            continue
        scope = (run["head_repository"]["full_name"], run["head_branch"])
        # Open PRs keep their own newest evidence. Closed branches compete
        # with main, with main preferred when it has corresponding evidence.
        lane = scope if scope in open_heads else None
        groups[(path, family(a["name"]), lane)].append(a)

    delete = []
    for (_, _, lane), entries in groups.items():
        def rank(a):
            run = runs[a["workflow_run"]["id"]]
            return (lane is None and run["head_branch"] == "main"
                    and run["head_repository"]["full_name"] == run["repository"]["full_name"],
                    run["created_at"], run["id"], a["created_at"], a["id"])
        newest = max(entries, key=rank)
        delete.extend(a for a in entries if a["id"] != newest["id"])

    if native_safe:
        for a in artifacts:
            run = runs[a["workflow_run"]["id"]]
            if (not a["expired"] and NATIVE.fullmatch(a["name"])
                    and workflow_path(run) == ".github/workflows/build-native-codex.yml"
                    and run["status"] == "completed" and run["id"] not in native_keep):
                delete.append(a)
    return sorted(delete, key=lambda a: a["id"])


def active_consumers(repo):
    # Unfiltered first page is insufficient when many checks have run.
    for status in ("queued", "in_progress", "waiting", "requested", "pending"):
        for run in pages(repo, f"actions/runs?status={status}", "workflow_runs"):
            if workflow_path(run) in CONSUMERS:
                return True
    return False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    repo = args.repo
    artifacts = [a for a in pages(repo, "actions/artifacts", "artifacts")
                 if not a["expired"] and a.get("workflow_run")]
    # Resolve ownership from the workflow run, never from an artifact name
    # supplied by an arbitrary PR. Release artifacts remain outside the scope.
    runs = {}
    for a in artifacts:
        rid = a["workflow_run"]["id"]
        if rid not in runs:
            runs[rid] = api(repo, f"actions/runs/{rid}")
    prs = list(pages(repo, "pulls?state=open"))
    open_heads = {(p["head"]["repo"]["full_name"], p["head"]["ref"])
                  for p in prs if p["head"].get("repo")}

    native_keep = set()
    native_safe = False
    if any(NATIVE.fullmatch(a["name"]) for a in artifacts) and not active_consumers(repo):
        # Current main and every open PR must have a verified matching core.
        # If a new core is still needed, defer native removal to the next event.
        selector = ArtifactSelector()
        targets = [api(repo, "branches/main")["commit"]["sha"]]
        targets += [p["head"]["sha"] for p in prs]
        if all(p["head"].get("repo", {}).get("full_name") == repo for p in prs):
            for sha in dict.fromkeys(targets):
                if subprocess.run(["git", "cat-file", "-e", sha + "^{commit}"],
                                  capture_output=True).returncode:
                    break
                selected = selector.find(repo, sha, ["arm64-v8a"])
                if not selected:
                    break
                native_keep.add(int(selected))
            else:
                native_safe = True
    if not native_safe:
        print("原生内核暂缓清理：构建/发布仍在运行或当前源码缺少已验证内核。")

    candidates = plan(artifacts, runs, open_heads, native_keep, native_safe)
    native_deferred = False
    for a in candidates:
        rid = a["workflow_run"]["id"]
        # A rerun can begin after the initial snapshot.
        current = api(repo, f"actions/runs/{rid}")
        if current["status"] != "completed" or current["run_attempt"] != runs[rid]["run_attempt"]:
            print(f"跳过已重新运行的检查 {rid}")
            continue
        if NATIVE.fullmatch(a["name"]):
            if native_deferred or active_consumers(repo):
                native_deferred = True
                continue
        print(f"{'删除' if args.apply else '预览'}: {a['id']} {a['name']} (run {rid})")
        if args.apply:
            gh("api", "--method", "DELETE", f"repos/{repo}/actions/artifacts/{a['id']}")
    print(f"检查产物候选清理数：{len(candidates)}；正式 Release、标签、运行记录不在清理范围。")


if __name__ == "__main__":
    main()
