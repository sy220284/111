#!/usr/bin/env python3
"""Collect actual Android UI screenshots even when UI assertions fail.

The emulator runner invokes script lines independently, so all iteration and
gallery generation must be in this single Python process.
"""
from html import escape
from pathlib import Path
import subprocess
import json
import re

REQUIRED_SCREENS = """home sidebar chat settings-hub tools-sheet attachment-sheet engine-status model-sheet account appearance mode-sheet terminal-confirm evidence-sheet thread-library thread-full-search account-usage account-limits workspace-files chat-toolbar diff-reader plugins account-sessions plugin-search audit-providers audit-skills audit-apps audit-mcp audit-environments audit-security audit-config audit-diagnostics audit-queue audit-approvals audit-conversation-approval audit-provider-editor audit-config-editor audit-environment-editor audit-security-profiles audit-about audit-permission-request audit-thread-context audit-thread-history audit-advanced-timeline audit-thread-attachments audit-review-sheet audit-goals-sheet audit-mcp-elicitation audit-plugin-detail audit-sidebar-thread audit-sidebar-filtered audit-composer-running audit-composer-edit audit-rtl-200font-bulk""".split()
OPTIONAL_SCREENS = ["audit-thread-history-transition"]
DEVICE_ROOT = "/sdcard/Android/data/io.github.sy220284.codexandroid/files"
DESTINATION = Path("ui-screenshots")
SCREEN_NAME = re.compile(r"^[a-z0-9][a-z0-9_-]*-android35\.png$")


def parse_discovered_screens(stdout: str) -> list[str]:
    """Only accept screenshot basenames from the local Android evidence folder."""
    discovered = set()
    for raw in stdout.splitlines():
        name = raw.strip()
        if SCREEN_NAME.fullmatch(name):
            discovered.add(name.removesuffix("-android35.png"))
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



def main():
    DESTINATION.mkdir(parents=True, exist_ok=True)
    present = []
    missing = []
    discovered = discover_device_screens()
    # Required coverage still blocks regressions; new screenshots automatically
    # enter the artifact gallery without hand-maintained names in this script.
    for screen in sorted(set(REQUIRED_SCREENS + OPTIONAL_SCREENS + discovered)):
        name = f"{screen}-android35.png"
        result = subprocess.run(
            ["adb", "pull", f"{DEVICE_ROOT}/{name}", str(DESTINATION / name)],
            capture_output=True, text=True, check=False,
        )
        output = DESTINATION / name
        if result.returncode == 0 and output.is_file() and output.stat().st_size > 0:
            present.append(screen)
            print(f"SCREENSHOT_OK {screen}", flush=True)
        elif screen in REQUIRED_SCREENS:
            missing.append(screen)
            print(f"SCREENSHOT_MISSING {screen}: {result.stderr.strip()}", flush=True)
    (DESTINATION / "missing.txt").write_text(
        "\n".join(missing) + ("\n" if missing else ""), encoding="utf-8")
    (DESTINATION / "manifest.json").write_text(
        json.dumps({"required": REQUIRED_SCREENS, "discovered": discovered,
                    "present": present, "missing": missing},
                   ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    pngs = sorted(DESTINATION.glob("*.png"))
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
        '<h1>Android 35 · 逐页截图</h1><small>本次实测捕获 ' + str(len(pngs)) +
        ' 张，必检 ' + str(len(REQUIRED_SCREENS)) + ' 张，缺少 ' +
        str(len(missing)) + ' 张。</small><div class="grid">' + cells + '</div></html>'
    )
    (DESTINATION / "index.html").write_text(html, encoding="utf-8")
    print(f"UI_CAPTURE_COUNTS present={len(present)} missing={len(missing)}", flush=True)
    # Caller checks ui-instrument-result.log and missing.txt after evidence was collected.
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
