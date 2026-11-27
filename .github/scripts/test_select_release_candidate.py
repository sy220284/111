#!/usr/bin/env python3
"""Release candidate continuity: fail closed on code, test and policy changes."""
import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from select_release_candidate import eligible, maintenance_only, select

A, B, C = ("a" * 40, "b" * 40, "c" * 40)


class ReleaseCandidateTests(unittest.TestCase):
    def test_known_maintenance_and_empty_delta(self):
        self.assertTrue(maintenance_only([]))
        self.assertTrue(maintenance_only([
            "AGENTS.md", "README.md", "docs/普通说明.md",
        ]))

    def test_client_native_acceptance_and_release_inputs_are_never_exempt(self):
        for path in (
            "android-app/app/src/main/java/Main.kt",
            "android-app/app/src/androidTest/java/Test.kt",
            "android-app/tools/check_protocol_schema_alignment.py",
            "upstream/codex/codex-rs/core/src/lib.rs",
            "docs/设计系统.md",
            "docs/ui-assets/screen.png",
            ".github/workflows/release.yml",
            ".github/workflows/build-android.yml",
            ".github/workflows/check-android-ui.yml",
            ".github/scripts/verify_release_acceptance.py",
            ".github/scripts/select_release_candidate.py",
            ".github/scripts/prepare-release-assets.sh",
            ".github/release-version",
            ".github/scripts/unknown.py",
        ):
            with self.subTest(path=path):
                self.assertFalse(maintenance_only(["AGENTS.md", path]))

    @patch("select_release_candidate.find_acceptance")
    @patch("select_release_candidate.eligible", return_value=True)
    def test_trigger_requires_both_gates_on_trigger_sha(self, candidate, acceptance):
        acceptance.side_effect = [None]
        self.assertIsNone(select("org/repo", B, A))
        self.assertEqual([c.args[1] for c in acceptance.call_args_list], [A])
        acceptance.reset_mock(side_effect=True)
        acceptance.side_effect = [123]
        self.assertEqual(select("org/repo", B, A), A)

    @patch("select_release_candidate.find_acceptance")
    @patch("select_release_candidate.eligible", return_value=False)
    def test_superseded_candidate_is_not_checked(self, candidate, acceptance):
        self.assertIsNone(select("org/repo", B, A))
        acceptance.assert_not_called()

    @patch("select_release_candidate.first_parent_revisions", return_value=[C, B, A])
    @patch("select_release_candidate.eligible", side_effect=[True, True])
    @patch("select_release_candidate.find_acceptance", side_effect=[None, 123, 456])
    def test_manual_recovers_verified_maintenance_ancestor(self, acceptance, candidate, revisions):
        self.assertEqual(select("org/repo", C), B)

    @patch("select_release_candidate.first_parent_revisions", return_value=[C, B, A])
    @patch("select_release_candidate.eligible", side_effect=[True, False])
    @patch("select_release_candidate.find_acceptance", return_value=None)
    def test_manual_never_crosses_unverified_input_change(self, acceptance, candidate, revisions):
        self.assertIsNone(select("org/repo", C))
        self.assertEqual(acceptance.call_count, 1)

    def test_real_git_ancestry_and_rename(self):
        previous = os.getcwd()
        with tempfile.TemporaryDirectory() as directory:
            try:
                os.chdir(directory)
                def git(*args):
                    return subprocess.check_output(["git", *args], text=True).strip()
                git("init", "-q")
                git("config", "user.email", "ci@example.invalid")
                git("config", "user.name", "CI")
                Path("AGENTS.md").write_text("rules", encoding="utf-8")
                Path("client.kt").write_text("client", encoding="utf-8")
                git("add", ".")
                git("commit", "-qm", "base")
                base = git("rev-parse", "HEAD")
                Path("AGENTS.md").write_text("new rules", encoding="utf-8")
                git("commit", "-qam", "maintenance")
                maintenance = git("rev-parse", "HEAD")
                self.assertTrue(eligible(base, maintenance))
                git("rm", "AGENTS.md")
                git("mv", "client.kt", "AGENTS.md")
                git("commit", "-qm", "rename client to allowed path")
                unsafe = git("rev-parse", "HEAD")
                self.assertFalse(eligible(maintenance, unsafe))
                with self.assertRaises(ValueError):
                    eligible(unsafe, maintenance)
            finally:
                os.chdir(previous)


if __name__ == "__main__":
    unittest.main()
