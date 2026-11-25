#!/usr/bin/env python3
"""Collect actual Android UI screenshots even when UI assertions fail.

The emulator runner invokes script lines independently, so all iteration and
gallery generation must be in this single Python process.
"""
import argparse
from html import escape
from pathlib import Path
import subprocess
import struct
import zlib
import json
import re

from device_matrix import API, RELEASE
from ui_evidence_manifest import load_evidence_manifest, required_screens, declared_screens

EVIDENCE_MANIFEST = load_evidence_manifest(Path(__file__).resolve().parents[2])
REQUIRED_SCREENS = required_screens(EVIDENCE_MANIFEST)
OPTIONAL_SCREENS = [screen for screen in declared_screens(EVIDENCE_MANIFEST) if screen not in REQUIRED_SCREENS]
DEVICE_ROOT = "/sdcard/Android/data/io.github.sy220284.codexandroid/files"
DESTINATION = Path("ui-screenshots")
SCREEN_NAME = re.compile(r"^[a-z0-9][a-z0-9_-]*-android\.png$")


def parse_discovered_screens(stdout: str) -> list[str]:
    """Only accept screenshot basenames from the local Android evidence folder."""
    discovered = set()
    for raw in stdout.splitlines():
        name = raw.strip()
        if SCREEN_NAME.fullmatch(name):
            discovered.add(name.removesuffix("-android.png"))
    return sorted(discovered)


def discover_device_screens() -> list[str]:
    result = subprocess.run(
        ["adb", "shell", "ls", "-1", DEVICE_ROOT],
        capture_output=True, text=True, check=False,
    )
    if result.returncode != 0:
        print("SCREENSHOT_DISCOVERY_ERROR " + result.stderr.strip(), flush=True)
        return []
    return parse_discovered_screens(result.stdout)



def valid_png(path):
    """Reject truncated files and corrupted chunks, not just invalid signatures."""
    if not path.is_file():
        return False
    data = path.read_bytes()
    if not data.startswith(b"\x89PNG\r\n\x1a\n"):
        return False
    offset, seen_header, seen_image = 8, False, False
    while offset + 12 <= len(data):
        length, kind = struct.unpack_from(">I4s", data, offset)
        end = offset + 12 + length
        if end > len(data):
            return False
        payload = data[offset + 8:end - 4]
        checksum = struct.unpack_from(">I", data, end - 4)[0]
        if zlib.crc32(kind + payload) != checksum:
            return False
        if not seen_header:
            if kind != b"IHDR" or length != 13:
                return False
            width, height = struct.unpack_from(">II", payload)
            if not width or not height:
                return False
            seen_header = True
        elif kind == b"IDAT":
            seen_image = True
        elif kind == b"IEND":
            return seen_image and length == 0 and end == len(data)
        offset = end
    return False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--destination", type=Path, default=DESTINATION)
    destination = parser.parse_args().destination
    destination.mkdir(parents=True, exist_ok=True)
    # Evidence directories belong to one run/configuration only.
    for old in destination.glob("*.png"):
        old.unlink()
    present = []
    dimensions = {}
    missing = []
    discovered = discover_device_screens()
    # Required coverage still blocks regressions; new screenshots automatically
    # enter the artifact gallery without hand-maintained names in this script.
    for screen in sorted(set(REQUIRED_SCREENS + OPTIONAL_SCREENS + discovered)):
        name = f"{screen}-android.png"
        result = subprocess.run(
            ["adb", "pull", f"{DEVICE_ROOT}/{name}", str(destination / name)],
            capture_output=True, text=True, check=False,
        )
        output = destination / name
        if result.returncode == 0 and valid_png(output):
            present.append(screen)
            with output.open('rb') as png:
                png.seek(16)
                width, height = struct.unpack('>II', png.read(8))
            dimensions[screen] = {'width_px': width, 'height_px': height}
            print(f"SCREENSHOT_OK {screen}", flush=True)
        elif screen in REQUIRED_SCREENS:
            missing.append(screen)
            print(f"SCREENSHOT_MISSING {screen}: {result.stderr.strip()}", flush=True)
    (destination / "missing.txt").write_text(
        "\n".join(missing) + ("\n" if missing else ""), encoding="utf-8")
    (destination / "manifest.json").write_text(
        json.dumps({"required": REQUIRED_SCREENS, "discovered": discovered,
                    "present": present, "missing": missing, "screenshot_dimensions": dimensions},
                   ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    pngs = sorted(destination.glob("*.png"))
    cells = "".join(
        '<a class="card" href="' + escape(f.name) + '"><img loading="lazy" src="' +
        escape(f.name) + '" alt="' + escape(f.stem) + '"><span>' +
        escape(f.stem) + '</span></a>'
        for f in pngs
    )
    html = (
        '<!doctype html><html lang="zh-CN"><meta charset="utf-8">'
        '<meta name="viewport" content="width=device-width,initial-scale=1">'
        '<title>Codex Android · CI 界面验收</title>'
        '<style>body{font:15px system-ui,sans-serif;background:#f6f7f7;'
        'color:#263139;margin:24px}h1{font-size:24px}small{color:#56646d}'
        '.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(175px,1fr));'
        'gap:20px}.card{display:block;text-decoration:none;color:inherit;background:white;'
        'border:1px solid #d8dee1;border-radius:12px;padding:10px}'
        'img{width:100%;height:350px;object-fit:contain;display:block}'
        'span{display:block;font-size:12px;word-break:break-all;margin-top:9px}</style>'
        f'<h1>Android {RELEASE} (API {API}) · 逐页截图</h1><small>本次实测捕获 ' + str(len(pngs)) +
        ' 张，必检 ' + str(len(REQUIRED_SCREENS)) + ' 张，缺少 ' +
        str(len(missing)) + ' 张。</small><div class="grid">' + cells + '</div></html>'
    )
    (destination / "index.html").write_text(html, encoding="utf-8")
    print(f"UI_CAPTURE_COUNTS present={len(present)} missing={len(missing)}", flush=True)
    # Caller retains the manifest even when evidence is missing.
    return int(bool(missing))


if __name__ == "__main__":
    raise SystemExit(main())
