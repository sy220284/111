import unittest

from unittest.mock import patch
from retain_check_artifacts import plan, pr_head_signature, deletion_boundary_unchanged

REPO = "owner/111"


def run(id, path="check-android-ui", branch="main", status="completed"):
    return {"id": id, "path": ".github/workflows/" + path + ".yml",
            "head_branch": branch, "status": status, "created_at": f"{id:04}",
            "head_repository": {"full_name": REPO}, "repository": {"full_name": REPO}}


def artifact(id, rid, name="report", expired=False):
    return {"id": id, "workflow_run": {"id": rid}, "name": name,
            "created_at": f"{id:04}", "expired": expired}


class RetentionTests(unittest.TestCase):
    def test_main_latest_open_pr_and_running_are_protected(self):
        runs = {i: run(i, branch=b, status=s) for i, b, s in [
            (1, "main", "completed"), (2, "main", "completed"),
            (3, "merged", "completed"), (4, "open", "completed"),
            (5, "open", "completed"), (6, "main", "in_progress")]}
        entries = [artifact(i, i) for i in runs]
        self.assertEqual([1, 3, 4], [a["id"] for a in plan(
            entries, runs, {(REPO, "open")}, set(), False)])

    def test_deletion_boundary_rechecks_active_work_pr_heads_and_native_main(self):
        pr = {"number": 1, "head": {"repo": {"full_name": REPO}, "ref": "work", "sha": "old"},
              "base": {"ref": "main"}}
        baseline = pr_head_signature([pr])
        with patch("retain_check_artifacts.active_consumers", return_value=False), \
             patch("retain_check_artifacts.pages", return_value=[pr]), \
             patch("retain_check_artifacts.api", return_value={"commit": {"sha": "main"}}):
            self.assertTrue(deletion_boundary_unchanged(REPO, baseline, "main", True))
            self.assertFalse(deletion_boundary_unchanged(REPO, baseline, "old-main", True))
        with patch("retain_check_artifacts.active_consumers", return_value=True):
            self.assertFalse(deletion_boundary_unchanged(REPO, baseline, "main", False))
        changed = {**pr, "head": {**pr["head"], "sha": "new"}}
        with patch("retain_check_artifacts.active_consumers", return_value=False), \
             patch("retain_check_artifacts.pages", return_value=[changed]):
            self.assertFalse(deletion_boundary_unchanged(REPO, baseline, "main", False))

    def test_release_unknown_workflows_and_expired_never_deleted(self):
        runs = {1: run(1, "release"), 2: run(2, "release"),
                3: run(3, "unknown"), 4: run(4), 5: run(5)}
        entries = [artifact(i, i, expired=i == 4) for i in runs]
        self.assertEqual([], plan(entries, runs, set(), set(), True))

    def test_native_only_removed_when_verified_replacement_is_safe(self):
        runs = {i: run(i, "build-native-codex") for i in (1, 2, 3)}
        entries = [artifact(i, i, "codex-android-native-arm64-v8a") for i in runs]
        self.assertEqual([], plan(entries, runs, set(), {1}, False))
        self.assertEqual([2, 3], [a["id"] for a in plan(entries, runs, set(), {1}, True)])

    def test_different_device_sizes_and_reference_versions(self):
        runs = {1: run(1, "dsh-go-reference-audit"), 2: run(2, "dsh-go-reference-audit")}
        entries = [artifact(1, 1, "dsh-go-v1.3.0-ui-evidence"),
                   artifact(2, 2, "dsh-go-v1.4.0-ui-evidence"),
                   artifact(3, 1, "360dp"), artifact(4, 1, "411dp")]
        self.assertEqual([1], [a["id"] for a in plan(entries, runs, set(), set(), False)])

    def test_failed_main_cannot_remove_last_successful_visual_baseline(self):
        runs = {1: dict(run(1), conclusion="success"), 2: dict(run(2), conclusion="success"),
                3: dict(run(3), conclusion="failure")}
        items = [artifact(i,i,"codex-android-ui-visual-api37-compact-baseline") for i in runs]
        self.assertEqual([1], [a["id"] for a in plan(items,runs,set(),set(),False)])

    def test_rerun_duplicate_retains_newest_artifact(self):
        runs = {1: run(1)}
        self.assertEqual([1], [a["id"] for a in plan(
            [artifact(2, 1), artifact(1, 1)], runs, set(), set(), False)])


if __name__ == "__main__":
    unittest.main()
