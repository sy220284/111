import unittest
from datetime import datetime, timezone, timedelta
from artifact_governance import inventory, family, apply
from unittest.mock import patch

NOW=datetime(2026,11,27,tzinfo=timezone.utc)
def row(i,name,days=8,**kw):
    return dict(id=i,name=name,size_in_bytes=100,expired=False,digest="sha256:"+"a"*64,
                created_at=(NOW-timedelta(days=days)).isoformat(),workflow_run={"id":i})|kw
class GovernanceTests(unittest.TestCase):
    def test_preserves_newest_three_failed_recent_and_all_functional_or_reusable(self):
        name="codex-android-ui-visual-api37-mainstream-baseline"
        rows=[row(i,name,days=i) for i in range(1,11)]
        rows.extend([row(20,"ui-shard-mainstream-baseline-navigation"),
                     row(21,"111-runtime-arm64-v8a-"+"c"*64),
                     row(22,"111-runtime-arm64-v8a-"+"c"*64),
                     row(23,"release-candidate"),row(24,"unknown")])
        runs={i:{"status":"completed","conclusion":"success"} for i in range(1,25)}
        runs[8]["conclusion"]="failure";runs[9]["status"]="in_progress"
        p=inventory(rows,runs,NOW)
        self.assertEqual([7,10],[x["id"] for x in p["observation_cleanup"]])
        self.assertEqual(200,p["observation_cleanup_bytes"])
        self.assertEqual(1500,p["active_bytes"])
        self.assertEqual([21,22],p["repeated_fingerprints"][0]["ids"])
    def test_expired_and_unknown_metadata(self):
        self.assertEqual("unknown-protected",family("ui-diagnostic-invented"))
        self.assertEqual(0,inventory([row(1,"x",expired=True)],now=NOW)["active_bytes"])
        self.assertEqual([],inventory([row(1,"x")],now=NOW)["observation_cleanup"])
    def test_delete_rechecks_identity_and_readback(self):
        a=row(1,"codex-android-ui-visual-api37-ultra-adaptive")
        item={k:a[k] for k in ("id","name","size_in_bytes","created_at","digest")}|{"run_id":1}
        plan={"observation_cleanup":[item]}
        import json
        with patch("artifact_governance.api",side_effect=[json.dumps(a),json.dumps({"status":"completed","conclusion":"success"}),""]) as api,patch("artifact_governance.listing",return_value=[]):
            self.assertEqual(1,apply("o/r",plan))
            self.assertEqual(("o/r","actions/artifacts/1","-X","DELETE"),api.call_args.args)
        with patch("artifact_governance.api",return_value=json.dumps(a|{"digest":"changed"})) as api,self.assertRaises(ValueError):
            apply("o/r",plan)
        self.assertEqual(1,api.call_count)
