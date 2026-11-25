#!/usr/bin/env python3
"""Retain current Actions evidence without touching releases or run history."""
import argparse
import json
import re
import subprocess
from collections import defaultdict
from urllib.parse import quote

from select_native_artifacts import ArtifactSelector, configured_abis

CHECK_WORKFLOWS = {
    ".github/workflows/ci.yml",
    ".github/workflows/build-android.yml",
    ".github/workflows/build-native-codex.yml",
    ".github/workflows/check-android-ui.yml",
    ".github/workflows/dsh-go-reference-audit.yml",
    ".github/workflows/rehearse-android-upgrade.yml",
    ".github/workflows/import-codex.yml",
}
NATIVE = re.compile(r"^(?:codex-android-native-(?:arm64-v8a|x86_64)|native-index-(?:arm64-v8a|x86_64)-[0-9a-f]{64}|provider-index-[0-9a-f]{64})$")
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
        scope = ((run.get("head_repository") or {}).get("full_name"), run["head_branch"])
        # Open PRs keep their own newest evidence. Closed branches compete
        # with main, with main preferred when it has corresponding evidence.
        lane = scope if scope in open_heads else None
        groups[(path, family(a["name"]), lane)].append(a)

    delete = []
    for (_, _, lane), entries in groups.items():
        def rank(a):
            run = runs[a["workflow_run"]["id"]]
            return (lane is None and run["head_branch"] == "main"
                    and (run.get("head_repository") or {}).get("full_name") == run["repository"]["full_name"],
                    run["created_at"], run["id"], a["created_at"], a["id"])
        newest = max(entries, key=rank)
        delete.extend(a for a in entries if a["id"] != newest["id"])

    if native_safe:
        for a in artifacts:
            run = runs[a["workflow_run"]["id"]]
            if (not a["expired"] and NATIVE.fullmatch(a["name"])
                    and workflow_path(run) in {".github/workflows/build-native-codex.yml", ".github/workflows/ci.yml"}
                    and run["status"] == "completed" and run["id"] not in native_keep):
                delete.append(a)
    return sorted(delete, key=lambda a: a["id"])


def active_runs(repo, branch=None):
    # Unfiltered first page is insufficient when many checks have run.
    scope = "" if branch is None else "&branch=" + quote(branch, safe="")
    for status in ("queued", "in_progress", "waiting", "requested", "pending"):
        yield from pages(repo, f"actions/runs?status={status}{scope}", "workflow_runs")


def active_consumers(repo):
    return any(workflow_path(run) in CONSUMERS for run in active_runs(repo))


def pr_head_signature(prs):
    return frozenset((p["number"], (p["head"].get("repo") or {}).get("full_name"),
                      p["head"]["ref"], p["head"]["sha"], p["base"]["ref"])
                     for p in prs)


def deletion_boundary_unchanged(repo, original_prs, main_sha, native):
    if active_consumers(repo):
        return False
    if pr_head_signature(list(pages(repo, "pulls?state=open"))) != original_prs:
        return False
    if native and api(repo, "branches/main")["commit"]["sha"] != main_sha:
        return False
    return True


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
    needed = {a["workflow_run"]["id"] for a in artifacts}
    runs = {}
    if needed:
        for run in pages(repo, "actions/runs", "workflow_runs"):
            if run["id"] in needed:
                runs[run["id"]] = run
                needed.remove(run["id"])
                if not needed:
                    break
    for rid in sorted(needed):
        runs[rid] = api(repo, f"actions/runs/{rid}")
    prs = list(pages(repo, "pulls?state=open"))
    open_heads = {(p["head"]["repo"]["full_name"], p["head"]["ref"])
                  for p in prs if p["head"].get("repo")}

    original_prs = pr_head_signature(prs)
    main_sha = api(repo, "branches/main")["commit"]["sha"]
    native_keep = set()
    native_safe = False
    if any(NATIVE.fullmatch(a["name"]) for a in artifacts) and not active_consumers(repo):
        # Current main and every open PR must have a verified matching core.
        # If a new core is still needed, defer native removal to the next event.
        selector = ArtifactSelector()
        targets = [main_sha]
        targets += [p["head"]["sha"] for p in prs]
        if all((p["head"].get("repo") or {}).get("full_name") == repo for p in prs):
            for sha in dict.fromkeys(targets):
                if subprocess.run(["git", "cat-file", "-e", sha + "^{commit}"],
                                  capture_output=True).returncode:
                    break
                recipe = selector.run(["git", "show", f"{sha}:.github/workflows/build-native-codex.yml"])
                required_abis = configured_abis(recipe)
                if "arm64-v8a" not in required_abis:
                    break
                selected = {abi: selector.find(repo, sha, [abi]) for abi in required_abis}
                if not selected.get("arm64-v8a"):
                    break
                native_keep.update(int(run_id) for run_id in selected.values() if run_id)
                provider = selector.find_provider(repo, sha)
                if not provider:
                    break
                native_keep.add(int(provider))
            else:
                native_safe = True
    if not native_safe:
        print("原生内核暂缓清理：构建/发布仍在运行或当前源码缺少已验证内核。")

    candidates = plan(artifacts, runs, open_heads, native_keep, native_safe)
    removed = 0
    for a in candidates:
        rid = a["workflow_run"]["id"]
        native = bool(NATIVE.fullmatch(a["name"]))
        # Re-read the run for every artifact, even siblings from the same run.
        current = api(repo, f"actions/runs/{rid}")
        if current["status"] != "completed" or current["run_attempt"] != runs[rid]["run_attempt"]:
            print(f"跳过已重新运行的检查 {rid}")
            continue
        if args.apply and not deletion_boundary_unchanged(repo, original_prs, main_sha, native):
            print("检查任务、开放 PR 或原生输入基线已变化，本次剩余清理暂缓")
            break
        print(f"{'删除' if args.apply else '预览'}: {a['id']} {a['name']} (run {rid})")
        if args.apply:
            gh("api", "--method", "DELETE", f"repos/{repo}/actions/artifacts/{a['id']}")
            removed += 1
    print(f"候选 {len(candidates)} 项，实际删除 {removed} 项；正式 Release、标签、运行记录不在清理范围。")


if __name__ == "__main__":
    main()
