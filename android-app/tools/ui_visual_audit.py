#!/usr/bin/env python3
"""Inspect every required screenshot and compare same-profile accepted main evidence.

Visual changes are review findings (legitimate redesigns can change pixels).
Invalid, blank, mismatched or absent required screenshots fail the job.
"""
import argparse
import base64
import json
import os
import subprocess
import tempfile
import re
from html import escape
from pathlib import Path
from PIL import Image, ImageStat, ImageChops
from device_matrix import API, dimensions
from ui_evidence_manifest import load_evidence_manifest, required_screens

SIZE = (64, 128)
VERSION = 2
REQUIRED = required_screens(load_evidence_manifest(Path(__file__).resolve().parents[2]))

def signature(path, metadata):
    with Image.open(path) as img:
        img.load()
        width, height = img.size
        if width < 32 or height < 32:
            raise ValueError(f"Invalid screenshot size: {img.size}")
        physical = [int(v) for v in metadata["physical_size"].split("x")]
        if width > max(physical[0], metadata["width_px"]) or height > max(physical[1], metadata["height_px"]):
            raise ValueError(f"Screenshot exceeds actual display: {img.size}")
        rgba = img.convert("RGBA")
        if rgba.getchannel("A").getextrema() == (0, 0):
            raise ValueError("Fully transparent screenshot")
        rgb = rgba.convert("RGB")
        thumb = rgb.resize(SIZE, Image.Resampling.BOX)
        colors = rgb.resize((128, 256), Image.Resampling.BOX).getcolors(128 * 256)
        deviation = max(ImageStat.Stat(thumb).stddev)
        if len(colors) < 2 or deviation < 1.5:
            raise ValueError("Blank or nearly uniform screenshot")
        return {"width": width, "height": height,
                "rgb": base64.b64encode(thumb.tobytes()).decode(),
                "color_count": len(colors), "stddev": round(deviation, 3)}

def diff(old, new):
    a, b = (base64.b64decode(x["rgb"], validate=True) for x in (old, new))
    if len(a) != SIZE[0] * SIZE[1] * 3 or len(a) != len(b):
        raise ValueError("Invalid baseline RGB signature")
    mean = sum(abs(x-y) for x,y in zip(a,b)) / (len(a)*255)
    changed = sum(max(abs(a[i+j]-b[i+j]) for j in range(3)) >= 24 for i in range(0,len(a),3))/(len(a)/3)
    resized = (old["width"],old["height"]) != (new["width"],new["height"])
    return {"mean_rgb_delta": round(mean,5), "changed_area": round(changed,5),
            "resized": resized, "review": resized or mean >= .035 or changed >= .15}

def gh(*args):
    return subprocess.check_output(["gh", *args], text=True)

def baseline(repo, profile, mode):
    name = f"codex-android-ui-visual-api{API}-{profile}-{mode}"
    # Only nonexpired artifacts from successful main CI; never from PRs or this current run.
    pages = json.loads(gh("api", "--paginate", "--slurp",
                         f"repos/{repo}/actions/artifacts?name={name}&per_page=100"))
    artifacts = [a for p in pages for a in p["artifacts"] if not a["expired"]
                 and a.get("workflow_run", {}).get("head_branch") == "main"]
    for a in sorted(artifacts, key=lambda x: x["created_at"], reverse=True):
        run_id = a["workflow_run"]["id"]
        if str(run_id) == os.environ.get("GITHUB_RUN_ID"):
            continue
        run = json.loads(gh("api", f"repos/{repo}/actions/runs/{run_id}"))
        if (run["conclusion"] != "success" or run["head_branch"] != "main"
            or run["event"] not in ("push", "workflow_dispatch")
            or run["path"].split("@")[0] != ".github/workflows/ci.yml"):
            continue
        with tempfile.TemporaryDirectory() as folder:
            gh("run", "download", str(run_id), "--repo", repo, "--name", name, "--dir", folder)
            record = json.loads((Path(folder)/"fingerprints.json").read_text())
        if (record["commit"] != run["head_sha"] or record["profile"] != profile
            or record["api"] != API or record["version"] != VERSION or record["errors"]
            or set(record["modes"]) != {mode}):
            raise ValueError("Main visual baseline provenance mismatch")
        return record
    return None

def audit(profile, root, mode):
    source = os.environ.get("TEST_SOURCE_SHA") or os.environ.get("GITHUB_SHA")
    if not source:
        raise ValueError("Missing source SHA")
    record = {"version": VERSION, "api": API, "profile": profile, "commit": source,
              "modes": {}, "errors": []}
    for mode, folder in [(mode, "ui-screenshots" if mode == "baseline" else "ui-screenshots-adaptive")]:
        path = root/folder
        try:
            manifest = json.loads((path/"manifest.json").read_text())
            if (manifest.get("commit") != source or manifest.get("profile") != profile
                or manifest.get("mode") != mode or manifest.get("android_api") != API):
                raise ValueError("Screenshot manifest does not match actual source or configuration")
            expected = dimensions(profile, mode)
            if any(manifest.get(key) != value for key,value in expected.items()):
                raise ValueError("Screenshot device geometry or font mismatch")
            if manifest.get("missing") or set(REQUIRED) - set(manifest["present"]):
                raise ValueError("Required screenshots missing")
            values = {}
            for screen in manifest["present"]:
                if not re.fullmatch(r"[a-z0-9][a-z0-9_-]*",screen):
                    raise ValueError("Invalid screenshot scene name")
                try:
                    values[screen] = signature(path/(screen+"-android.png"),manifest)
                except (OSError,ValueError) as exc:
                    record["errors"].append(f"{mode}/{screen}: {exc}")
            record["modes"][mode] = {"configuration": expected, "build_fingerprint":manifest["build_fingerprint"], "screens": values}
        except (OSError, ValueError, KeyError, TypeError) as exc:
            record["errors"].append(f"{mode}: {exc}")
    return record

def compare(previous,current):
    if previous is None:
        return {"status":"baseline_pending","reason":"No successful same-profile main visual artifact yet","changes":[]}
    changes = []
    for mode, data in current["modes"].items():
        old = previous["modes"].get(mode)
        if not old or old["configuration"] != data["configuration"] or old.get("build_fingerprint") != data.get("build_fingerprint"):
            return {"status":"baseline_pending","reason":"Device configuration or OS build changed","changes":[]}
        for name in sorted(set(data["screens"]) | set(old["screens"])):
            if name not in old["screens"] or name not in data["screens"]:
                changes.append({"mode":mode,"screen":name,"review":True,"change":"added" if name in data["screens"] else "removed"})
            else:
                changes.append({"mode":mode,"screen":name,**diff(old["screens"][name],data["screens"][name])})
    return {"status":"compared","baseline_commit":previous["commit"],"changes":changes}

def write_gallery(previous, current, report, output):
    if report["status"] != "compared":
        return
    folder = output/"review"
    folder.mkdir(exist_ok=True)
    cells = []
    for x in report["changes"]:
        if not x["review"]:
            continue
        mode,name=x["mode"],x["screen"]
        old=previous["modes"][mode]["screens"].get(name)
        new=current["modes"][mode]["screens"].get(name)
        def image(record, suffix):
            if record is None:
                return None
            data=base64.b64decode(record["rgb"],validate=True)
            img=Image.frombytes("RGB",SIZE,data)
            img.save(folder/f"{mode}-{name}-{suffix}.png")
            return img
        a,b=image(old,"old"),image(new,"new")
        if a is not None and b is not None:
            heat=ImageChops.difference(a,b).point(lambda n:min(255,n*4))
            heat.save(folder/f"{mode}-{name}-diff.png")
        thumbs=[]
        for suffix,label,present in [("old","上次通过",a),("new","当前",b),("diff","差异",a is not None and b is not None)]:
            if present is not None and present is not False:
                url=f"review/{mode}-{name}-{suffix}.png"
                thumbs.append(f'<figure><img src="{escape(url)}"><figcaption>{label}</figcaption></figure>')
            else:
                thumbs.append(f"<figure>{label}：无</figure>")
        cells.append(f"<section><h3>{escape(mode+'/'+name)}</h3><p>{escape(str(x))}</p><div>"+ "".join(thumbs)+"</div></section>")
    (output/"index.html").write_text("<!doctype html><html lang='zh-CN'><meta charset='utf-8'><title>UI 视觉变化</title><style>body{font:14px system-ui;margin:24px}section{border-top:1px solid #ccc;padding:12px}section div{display:flex;gap:12px}figure{margin:0}img{width:128px;height:256px;image-rendering:pixelated}</style><h1>同配置主分支视觉差异</h1>"+ "".join(cells)+"</html>",encoding="utf-8")

def main():
    p=argparse.ArgumentParser()
    p.add_argument("--profile",required=True)
    p.add_argument("--repo",required=True)
    p.add_argument("--mode",choices=("baseline","adaptive"),required=True)
    args=p.parse_args()
    output=Path("ui-visual-evidence");output.mkdir(exist_ok=True)
    record=audit(args.profile,Path.cwd(),args.mode)
    report={"structural_errors":record["errors"]}
    if not record["errors"]:
        try:
            previous=baseline(args.repo,args.profile,args.mode)
            report.update(compare(previous,record))
            write_gallery(previous,record,report,output)
        except (OSError, ValueError, KeyError, TypeError, subprocess.CalledProcessError) as exc:
            record["errors"].append("Historical visual evidence lookup failed: " + str(exc))
            report["status"] = "failed"
    else:
        report["status"]="failed"
    (output/"fingerprints.json").write_text(json.dumps(record,ensure_ascii=False))
    (output/"report.json").write_text(json.dumps(report,ensure_ascii=False,indent=2))
    summary=Path(os.environ["GITHUB_STEP_SUMMARY"])
    with summary.open("a",encoding="utf-8") as file:
        file.write(f"\n## UI 视觉审计 {args.profile}\n状态：{report['status']}；结构问题：{len(record['errors'])}；需审阅的变化：{sum(x['review'] for x in report.get('changes',[]))}\n")
        for x in report.get("changes",[]):
            if x["review"]:
                file.write(f"- {x['mode']}/{x['screen']}: {x.get('change',x.get('mean_rgb_delta'))}\n")
    return int(bool(record["errors"]))

if __name__=="__main__":
    raise SystemExit(main())
