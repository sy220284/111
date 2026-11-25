import unittest
from datetime import datetime, timezone
from unittest.mock import patch
from repository_steward import (merged_candidates, cache_candidates, protected_refs,
                                branch_deletion_still_safe, current_cache_candidate)


def branch(n, sha="abc", protected=False):
    return {"name": n, "commit": {"sha": sha}, "protected": protected}


def pr(n, ref, sha="abc", merged=True):
    return {"number": n, "merged_at": "yes" if merged else None,
            "head": {"ref": ref, "sha": sha, "repo": {"full_name": "a/b"}},
            "base": {"ref": "main"}}


class StewardTests(unittest.TestCase):
    def test_exact_merged_head_only(self):
        branches = [branch("a"), branch("b", "new"), branch("c"), branch("main")]
        self.assertEqual(["a"], [b["name"] for b in merged_candidates(
            "a/b", branches, [pr(1, "a"), pr(2, "b"), pr(3, "c", merged=False), pr(4, "main")], set())])

    def test_protected_open_active_and_release(self):
        names = ["a", "b", "c", "release/x", "archive/x", "audit/dsh-go-ui-journey-20261123"]
        branches = [branch(n, protected=n == "a") for n in names]
        blocked = protected_refs([pr(1, "b")], [{"head_branch": "c"}])
        self.assertEqual([], merged_candidates("a/b", branches, [pr(i,n) for i,n in enumerate(names)], blocked))

    def test_branch_recheck_protects_new_jobs_and_changed_main(self):
        with patch("repository_steward.pages", return_value=[]) as pages, \
             patch("repository_steward.active_runs", return_value=[{"id": 999, "head_branch": "old"}]), \
             patch("repository_steward.api", return_value={"commit": {"sha": "head"}}):
            self.assertFalse(branch_deletion_still_safe("a/b", "old", "head"))
            self.assertTrue(branch_deletion_still_safe("a/b", "other", "head"))
            self.assertFalse(branch_deletion_still_safe("a/b", "other", "previous"))
            self.assertTrue(pages.called)

    def test_cache_recheck_protects_recent_access_or_recreated_branch(self):
        now = datetime(2026, 11, 24, tzinfo=timezone.utc)
        old = {"id": 3, "ref": "refs/heads/gone", "last_accessed_at": "2026-11-20T00:00:00Z"}
        from subprocess import CompletedProcess
        with patch("repository_steward.pages", return_value=[old]), \
             patch("repository_steward.active_runs", return_value=[]) as runs, \
             patch("repository_steward.subprocess.run", return_value=CompletedProcess([], 2)):
            self.assertEqual(old, current_cache_candidate("a/b", old, now))
            runs.assert_called_once_with("a/b", branch="gone")
        with patch("repository_steward.pages", return_value=[dict(old, last_accessed_at="2026-11-23T23:00:00Z")]), \
             patch("repository_steward.active_runs") as runs:
            self.assertIsNone(current_cache_candidate("a/b", old, now))
            runs.assert_not_called()
        with patch("repository_steward.pages", return_value=[old]), \
             patch("repository_steward.active_runs", return_value=[]), \
             patch("repository_steward.subprocess.run", return_value=CompletedProcess([], 0)):
            self.assertIsNone(current_cache_candidate("a/b", old, now))
        with patch("repository_steward.pages", return_value=[old]), \
             patch("repository_steward.active_runs", return_value=[{"id": 999}]), \
             patch("repository_steward.subprocess.run") as git:
            self.assertIsNone(current_cache_candidate("a/b", old, now))
            git.assert_not_called()

    def test_closed_pr_cache_rechecks_only_target_and_active_jobs(self):
        now = datetime(2026, 11, 24, tzinfo=timezone.utc)
        old = {"id": 4, "ref": "refs/pull/12/merge", "last_accessed_at": "2026-11-20T00:00:00Z"}
        with patch("repository_steward.pages", return_value=[old]), \
             patch("repository_steward.api", return_value={"state": "closed"}) as api, \
             patch("repository_steward.active_runs", return_value=[]):
            self.assertEqual(old, current_cache_candidate("a/b", old, now))
            api.assert_called_once_with("a/b", "pulls/12")
        with patch("repository_steward.pages", return_value=[old]), \
             patch("repository_steward.api", return_value={"state": "open"}):
            self.assertIsNone(current_cache_candidate("a/b", old, now))

    def test_only_orphan_or_closed_cache_after_grace(self):
        now = datetime(2026, 11, 24, tzinfo=timezone.utc)
        refs = ["refs/heads/main", "refs/heads/gone", "refs/pull/3/merge",
                "refs/pull/4/merge", "refs/tags/v1", "refs/heads/recent"]
        caches = [{"id": i, "ref": ref, "last_accessed_at": "2026-11-23T23:00:00Z" if i == 5 else "2026-11-20T00:00:00Z"} for i,ref in enumerate(refs)]
        self.assertEqual([1,2], [c["id"] for c in cache_candidates(caches,[branch("main")],[pr(3,"gone")],[],now)])
        self.assertEqual([], cache_candidates(caches,[branch("main")],[pr(3,"gone")],[{"head_branch":"gone"}],now))


if __name__ == "__main__":
    unittest.main()
