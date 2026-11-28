#!/usr/bin/env python3
"""Validate the exact published upgrade baseline and candidate update manifest."""
import argparse
import hashlib
import json
from pathlib import Path
import re

def sha(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream,"sha256").hexdigest()

def published_checksum(path,name):
    matches=[]
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        m=re.fullmatch(r"([a-f0-9]{64}) [ *](?:\./)?([^\r\n]+)",line)
        if not m:
            raise ValueError("旧版校验清单格式错误")
        if m[2]==name:
            matches.append(m[1])
    if len(matches)!=1:
        raise ValueError("旧版正式 APK 缺少唯一校验值")
    return matches[0]

def verify(previous,previous_tag,sums,candidate,version):
    previous=Path(previous);candidate=Path(candidate)
    baseline=previous_tag.removeprefix("v")
    if not previous_tag.startswith("v") or not re.fullmatch(r"\d+\.\d+\.\d+-111\.\d+",baseline):
        raise ValueError("旧版正式标签格式错误")
    if not re.fullmatch(r"\d+\.\d+\.\d+-111\.\d+",version):
        raise ValueError("候选版本格式错误")
    if int(baseline.rsplit(".",1)[1])>=int(version.rsplit(".",1)[1]):
        raise ValueError("候选 versionCode 必须严格递增")
    # The release job verifies the APK's signed application and version identity.
    # The published checksum is indexed by the asset's actual filename.
    old_sha=sha(previous)
    if published_checksum(sums,previous.name)!=old_sha:
        raise ValueError("旧版 APK 与正式发布摘要不一致")
    manifest=json.loads((candidate/"update-manifest.json").read_text(encoding="utf-8"))
    if manifest.get("schema")!=2 or manifest.get("targetVersion")!=version or not isinstance(manifest.get("patches"),list):
        raise ValueError("候选更新清单身份不一致")
    target_name=f"codex-android-arm64-v8a-{version}.apk"
    target=candidate/target_name
    target_sha=sha(target)
    expected={"abi":"arm64-v8a","name":target_name,"size":target.stat().st_size,"sha256":target_sha}
    if manifest.get("apks")!=[expected]:
        raise ValueError("候选 APK 更新清单不匹配")
    patches=manifest["patches"]
    if len(patches)>1:
        raise ValueError("正式候选只允许匹配本次 ARM64 基线的增量")
    patch_result=None
    if patches:
        patch=patches[0]
        name=f"delta-{baseline}-to-{version}-arm64-v8a.hpatch"
        data=candidate/name
        expected_patch={"abi":"arm64-v8a","fromVersion":baseline,"toVersion":version,
                        "algorithm":"hdiffpatch-window-zstd-v1","asset":name,
                        "sourceSha256":old_sha,"targetSha256":target_sha,
                        "size":data.stat().st_size,"sha256":sha(data)}
        if patch!=expected_patch or not 0<expected_patch["size"]*100<expected["size"]*65:
            raise ValueError("候选增量的源、目标、摘要、大小或算法不一致")
        patch_result=expected_patch
    return {"baseline":{"version":baseline,"sha256":old_sha},
            "target":{"version":version,"sha256":target_sha},
            "patch":patch_result,"scope":"published-baseline-and-candidate-manifest",
            "status":"passed"}

def main():
    p=argparse.ArgumentParser(description=__doc__)
    for k in ("previous","previous-tag","sums","candidate","version","output"):
        p.add_argument("--"+k,required=True)
    a=p.parse_args()
    report=verify(a.previous,a.previous_tag,a.sums,a.candidate,a.version)
    Path(a.output).write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
if __name__=="__main__":
    main()
