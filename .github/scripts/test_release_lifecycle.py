import unittest
import tempfile
import json
from pathlib import Path
from release_lifecycle import ledger, selection, retirement_plan, version
def release(i):
    return {"id":i,"tag_name":f"v0.1.0-111.{i}","draft":False,"prerelease":False,"published_at":"2026-11-27"}
def commit(tag):
    return str(version(tag)[-1])*40
def retired(i):
    tag=release(i)["tag_name"]
    return {tag:{"tag":tag,"release_id":i,"commit":commit(tag),"reason":"验证缺陷"}}
class LifecycleTests(unittest.TestCase):
    def test_skip_retired_baseline_and_prevent_republishing_bad_source(self):
        rows=[release(i) for i in (1,2,3)]
        self.assertEqual("v0.1.0-111.2",selection(rows,retired(3),"a"*40,commit)["previous_tag"])
        self.assertEqual("v0.1.0-111.2",selection(rows,retired(3),"2"*40,commit)["existing"])
        with self.assertRaises(ValueError):
            selection(rows,retired(3),"3"*40,commit)
    def test_retire_only_explicit_old_identity_with_newer_healthy_release(self):
        rows=[release(i) for i in (1,2,3)]
        self.assertEqual(list(retired(2).values()),retirement_plan(rows,retired(2),commit))
        self.assertEqual([],retirement_plan([release(1),release(3)],retired(2),commit))
        for entry in (retired(3),{"v0.1.0-111.2":retired(2)["v0.1.0-111.2"]|{"release_id":99}}):
            with self.assertRaises(ValueError):
                retirement_plan(rows,entry,commit)
    def test_ledger_rejects_duplicates_and_unbound_commit(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/"ledger.json"
            good=list(retired(2).values())[0]
            for values in ([good,good],[good|{"commit":"main"}],[good|{"reason":""}]):
                p.write_text(json.dumps({"schema":1,"releases":values}))
                with self.assertRaises(ValueError): ledger(p)
            p.write_text(json.dumps({"schema":1,"releases":[good]}))
            self.assertEqual(retired(2),ledger(p))
