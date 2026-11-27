import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from verify_upgrade_assets import verify,sha
class UpgradeAssetTests(unittest.TestCase):
    def test_complete_full_and_incremental_and_tampering(self):
        with TemporaryDirectory() as d:
            root=Path(d);old="0.1.0-111.47";new="0.1.0-111.48"
            prev=root/f"codex-android-arm64-v8a-{old}.apk";prev.write_bytes(b"old"*100)
            sums=root/"SHA256SUMS.txt";sums.write_text(f"{sha(prev)}  {prev.name}\n")
            target=root/f"codex-android-arm64-v8a-{new}.apk";target.write_bytes(b"new"*100)
            patch=root/f"delta-{old}-to-{new}-arm64-v8a.hpatch";patch.write_bytes(b"delta")
            manifest={"schema":2,"targetVersion":new,"apks":[{"abi":"arm64-v8a","name":target.name,"size":target.stat().st_size,"sha256":sha(target)}],"patches":[]}
            m=root/"update-manifest.json";m.write_text(json.dumps(manifest))
            check=lambda:verify(prev,"v"+old,sums,root,new)
            self.assertIsNone(check()["patch"])
            delta={"abi":"arm64-v8a","fromVersion":old,"toVersion":new,"algorithm":"hdiffpatch-window-zstd-v1","asset":patch.name,
                   "sourceSha256":sha(prev),"targetSha256":sha(target),"size":patch.stat().st_size,"sha256":sha(patch)}
            manifest["patches"]=[delta];m.write_text(json.dumps(manifest))
            self.assertEqual(delta,check()["patch"])
            for k,value in (("sourceSha256","0"*64),("targetSha256","0"*64),("fromVersion",new),("asset","../escape"),("size",0)):
                manifest["patches"]=[delta|{k:value}];m.write_text(json.dumps(manifest))
                with self.assertRaises(ValueError): check()
            manifest["patches"]=[delta];m.write_text(json.dumps(manifest))
            prev.write_bytes(b"modified")
            with self.assertRaises(ValueError):check()
