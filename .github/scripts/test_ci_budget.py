import re
import unittest
from pathlib import Path
from ci_metrics import summarize

ROOT = Path(__file__).resolve().parents[1] / "workflows"

class BudgetTests(unittest.TestCase):
    def test_every_runner_is_bounded(self):
        for path in ROOT.glob("*.yml"):
            jobs = path.read_text().split("\njobs:\n", 1)[-1]
            blocks = re.split(r"(?m)^  [a-z][a-z0-9-]*:\s*$", jobs)
            for block in blocks:
                if re.search(r"(?m)^    runs-on:", block):
                    with self.subTest(workflow=path.name, job=block[:80]):
                        match = re.search(r"(?m)^    timeout-minutes: (\d+)\s*$", block)
                        self.assertIsNotNone(match)
                        self.assertTrue(0 < int(match.group(1)) <= 180)
    def test_v8_producer_and_contract_are_independent(self):
        native = (ROOT / "build-native-codex.yml").read_text()
        v8 = native.split("\n  v8:\n",1)[1].split("\n  native:\n",1)[0]
        self.assertNotIn("provider-contract", next(l for l in v8.splitlines() if l.startswith("    needs:")))
        consumer = native.split("\n  native:\n",1)[1].split("\n  provider-contract:\n",1)[0]
        self.assertIn("provider-contract", next(l for l in consumer.splitlines() if l.startswith("    needs:")))
    def test_metrics_exclude_unexecuted(self):
        jobs = [{"name":"build","status":"completed","conclusion":"success","started_at":"2026-01-01T00:01:00Z","completed_at":"2026-01-01T00:03:00Z"},{"name":"skip","status":"completed","conclusion":"skipped"},{"name":"quality","status":"in_progress"}]
        self.assertEqual(summarize(jobs,"2026-01-01T00:00:00Z")["runner_minutes"],2)
