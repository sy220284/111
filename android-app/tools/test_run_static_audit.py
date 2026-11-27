import json
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import TestCase
from unittest.mock import patch

from static_audit_inputs import AuditInputs, PRODUCTION
from run_static_audit import run


class StaticAuditTests(TestCase):
    def test_shared_inputs_read_each_file_once(self):
        with TemporaryDirectory() as tmp:
            root = Path(tmp)
            path = root / PRODUCTION / "Sample.kt"
            path.parent.mkdir(parents=True)
            path.write_text("original", encoding="utf-8")
            inputs = AuditInputs(root)
            self.assertEqual(inputs.text(path), "original")
            path.write_text("changed", encoding="utf-8")
            self.assertEqual(inputs.production[path], "original")
            self.assertEqual(inputs.text(path.relative_to(root)), "original")
            self.assertEqual(AuditInputs(root).text(path), "changed")

    def test_duplicate_filename_is_rejected(self):
        with self.assertRaises(ValueError):
            AuditInputs.names({Path("a/X.kt"): "a", Path("b/X.kt"): "b"})

    def test_all_categories_run_and_failure_is_not_hidden(self):
        with TemporaryDirectory() as tmp:
            root = Path(tmp)
            path = root / PRODUCTION / "Sample.kt"
            path.parent.mkdir(parents=True)
            path.write_text("source", encoding="utf-8")
            out = root / "reports"
            with patch("run_static_audit.protocol", return_value=[]) as a, \
                 patch("run_static_audit.functionality", side_effect=ValueError("missing evidence")) as b, \
                 patch("run_static_audit.design", return_value=[]) as c, \
                 patch("run_static_audit.architecture", return_value=[]) as d, \
                 patch("run_static_audit.architecture_boundaries", return_value=[]) as e:
                self.assertEqual(run(root, out), 1)
                for rule in (a, b, c, d, e):
                    rule.assert_called_once()
                self.assertIs(a.call_args.args[0], c.call_args.args[0])
            summary = json.loads((out / "static-audit-summary.json").read_text())
            self.assertEqual(summary["protocol"]["status"], "passed")
            self.assertEqual(summary["functionality"]["status"], "failed")
            self.assertIn("missing evidence", summary["functionality"]["errors"][0])
