import json
import tempfile
import unittest
import zipfile
from datetime import datetime,timezone
from pathlib import Path
from unittest.mock import patch
from ui_shard_reuse import APKS, INPUT, check_input, same_inputs, extract, direct_shard, verify_origin
from device_matrix import shard_job_name

SOURCE="a"*40
TARGET="b"*40
NOW=datetime(2026,11,26,tzinfo=timezone.utc)
AT=NOW.isoformat()
PROOF={"run_id":12,"attempt":1,"source":SOURCE}
RECORD={"source":TARGET,"profile":"mainstream","mode":"baseline","shard":"navigation",
        "apk":{name:"1"*64 for name in APKS}}

class ShardReuseTests(unittest.TestCase):
    def test_input_identity(self):
        check_input(RECORD,TARGET,"mainstream","baseline","navigation")
        self.assertTrue(same_inputs(RECORD,dict(RECORD,source=SOURCE)))
        self.assertFalse(same_inputs(RECORD,dict(RECORD,apk={name:"2"*64 for name in APKS})))
        with self.assertRaises(ValueError): check_input(RECORD,SOURCE,"mainstream","baseline","navigation")

    def test_safe_archive(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d); archive=root/"a.zip"
            with zipfile.ZipFile(archive,"w") as z:
                z.writestr(INPUT,json.dumps(RECORD))
                z.writestr("ui-screenshots/test-android.png",b"png")
            extract(archive,root/"ok")
            self.assertEqual(RECORD,json.loads((root/"ok"/INPUT).read_text()))
            for name in ("../escape","/absolute","ui-screenshots/../../escape","unexpected.py","ui-shard-origin.json"):
                with zipfile.ZipFile(archive,"w") as z:z.writestr(name,"bad")
                with self.assertRaises(ValueError):extract(archive,root/"bad")

    def test_direct_success_only(self):
        run={"id":12,"head_sha":SOURCE,"run_attempt":1,"head_repository":{"full_name":"o/r"},
             "path":".github/workflows/ci.yml","event":"pull_request","created_at":AT}
        job={"id":34,"name":shard_job_name("mainstream","baseline","navigation"),
             "head_sha":SOURCE,"run_attempt":1,"status":"completed","conclusion":"success",
             "started_at":AT,"completed_at":AT,
             "steps":[{"name":"在真实 Android 环境验证侧栏导航并生成首页截图","conclusion":"success"}]}
        artifact={"id":56,"digest":"sha256:"+"1"*64}
        def result(j=job,changes=(),r=run):
            with patch("ui_shard_reuse.ancestor",return_value=True),patch("ui_shard_reuse.delta",return_value=changes), \
                 patch("ui_shard_reuse.api",side_effect=[r,{"total_count":1,"jobs":[j]}]), \
                 patch("ui_shard_reuse.artifact_index",return_value={"ui-shard-mainstream-baseline-navigation":artifact}):
                return direct_shard("o/r",PROOF,TARGET,"mainstream","baseline","navigation",NOW)
        origin=result()
        self.assertEqual(34,origin["job_id"])
        self.assertEqual(artifact,origin["artifact"])
        self.assertIsNone(result(dict(job,conclusion="failure")))
        self.assertIsNone(result(dict(job,steps=[dict(job["steps"][0],conclusion="skipped")])))
        self.assertIsNone(result(changes=["android-app/app/src/main/Foo.kt"]))
        self.assertIsNone(result(r=dict(run,run_attempt=2)))
        with patch("ui_shard_reuse.direct_shard",return_value=origin):
            self.assertEqual(SOURCE,verify_origin("o/r",PROOF,TARGET,"mainstream","baseline","navigation",
                origin|{"input":dict(RECORD,source=SOURCE)},RECORD))
            with self.assertRaises(ValueError):verify_origin("o/r",PROOF,TARGET,"mainstream","baseline","navigation",
                origin|{"input":dict(RECORD,source=SOURCE),"job_id":999},RECORD)
