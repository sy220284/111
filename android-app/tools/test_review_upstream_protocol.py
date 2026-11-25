import subprocess
import tempfile
import unittest
from pathlib import Path

from review_upstream_protocol import REVIEW_PATHS, changes


class UpstreamReviewTest(unittest.TestCase):
    def test_detects_added_removed_and_modified_contract_objects(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            def git(*args):
                return subprocess.check_output(["git", "-C", tmp, *args], text=True).strip()
            git("init", "-q")
            git("config", "user.name", "test")
            git("config", "user.email", "test@example.com")
            for path in REVIEW_PATHS:
                target = root / path
                if path.endswith("app-server-protocol"):
                    target = target / "schema.json"
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text("voice: string\n", encoding="utf-8")
            git("add", ".")
            git("commit", "-qm", "reviewed")
            expected = {path: git("rev-parse", "HEAD:" + path) for path in REVIEW_PATHS}
            self.assertEqual([], changes(root, "HEAD", expected))
            tree_path = root / REVIEW_PATHS[0]
            (tree_path / "schema.json").write_text("durationMs: number\n", encoding="utf-8")
            (tree_path / "new-field.json").write_text("new", encoding="utf-8")
            (root / REVIEW_PATHS[1]).unlink()
            git("add", "-A")
            candidate = git("write-tree")
            pending = changes(root, candidate, expected)
            self.assertEqual({REVIEW_PATHS[0], REVIEW_PATHS[1]}, {p[0] for p in pending})
            self.assertIsNone(next(p[2] for p in pending if p[0] == REVIEW_PATHS[1]))
            self.assertEqual([], changes(root, "HEAD", expected))
            with self.assertRaises(ValueError):
                changes(root, "HEAD", {})
            with self.assertRaises(RuntimeError):
                changes(root, "invalid-candidate", expected)
