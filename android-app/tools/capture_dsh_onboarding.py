#!/usr/bin/env python3
"""在隔离模拟器中采集 DSH Go 首次使用页面，不连接真实账号或主机。"""
import json
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

APP = "io.github.ccch1mneyyy.dsh_app"
BOUND = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def adb(*args):
    return subprocess.run(["adb", *args], check=True, capture_output=True).stdout


def visible_nodes(xml):
    root = ET.fromstring(xml)
    return [
        {
            "label": (node.get("content-desc") or node.get("text") or "").strip(),
            "class": node.get("class", ""),
            "bounds": node.get("bounds", ""),
            "checked": node.get("checked") == "true",
            "clickable": node.get("clickable") == "true",
            "enabled": node.get("enabled") == "true",
        }
        for node in root.iter("node")
        if node.get("content-desc") or node.get("text")
    ]


def center(bounds):
    match = BOUND.fullmatch(bounds)
    if match is None:
        raise ValueError(f"Invalid Android bounds: {bounds!r}")
    x1, y1, x2, y2 = map(int, match.groups())
    return ((x1 + x2) // 2, (y1 + y2) // 2)


def capture(out, title):
    time.sleep(2)
    adb("shell", "uiautomator", "dump", "/sdcard/window.xml")
    xml = adb("shell", "cat", "/sdcard/window.xml").decode("utf-8")
    nodes = visible_nodes(xml)
    (out / f"{title}.xml").write_text(xml, encoding="utf-8")
    (out / f"{title}.json").write_text(
        json.dumps(nodes, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    (out / f"{title}.png").write_bytes(adb("exec-out", "screencap", "-p"))
    print(f"SCREEN {title}: {[node['label'] for node in nodes]}", flush=True)
    return nodes


def find(nodes, labels):
    for target in labels:
        for node in nodes:
            if node["label"].casefold() == target.casefold() and node["enabled"]:
                return node
    for target in labels:
        for node in nodes:
            if target.casefold() in node["label"].casefold() and node["enabled"]:
                return node
    return None


def tap(node):
    x, y = center(node["bounds"])
    adb("shell", "input", "tap", str(x), str(y))
    print(f"TAP {node['label']!r} at ({x},{y})", flush=True)


def main():
    out = Path(sys.argv[1] if len(sys.argv) > 1 else "dsh-reference/flow")
    out.mkdir(parents=True, exist_ok=True)
    adb("shell", "pm", "grant", APP, "android.permission.POST_NOTIFICATIONS")
    screens = []
    try:
        nodes = capture(out, "00-welcome")
        screens.append("00-welcome")

        # 官方 APK 默认勾选了匿名统计；参考测试只观察流程，不发送统计。
        telemetry = find(nodes, ["Send anonymous usage stats"])
        if telemetry is not None and telemetry["checked"]:
            tap(telemetry)
            nodes = capture(out, "00-welcome-optout")
            screens.append("00-welcome-optout")
            changed = find(nodes, ["Send anonymous usage stats"])
            if changed is None or changed["checked"]:
                raise AssertionError("Disabling usage statistics did not change switch state")

        start = find(nodes, ["Start"])
        if start is None:
            raise AssertionError("DSH Go welcome screen has no available Start button")
        tap(start)
        nodes = capture(out, "01-runtime-choice")
        screens.append("01-runtime-choice")
        if find(nodes, ["Start"]) is not None:
            raise AssertionError("Start did not navigate away from the welcome screen")

        # 观察远程主机引导，无需安装 Linux 或填写凭据。
        remote = find(nodes, ["Remote host", "Use a remote host"])
        if remote is not None:
            tap(remote)
            nodes = capture(out, "02-remote-selected")
            screens.append("02-remote-selected")
            go = find(nodes, ["Continue"])
            if go is not None:
                tap(go)
                nodes = capture(out, "03-remote-setup")
                screens.append("03-remote-setup")

        # 到需要真实登录或主机凭据处即停止，避免生成外部账号操作。
        (out / "summary.json").write_text(
            json.dumps({"package": APP, "screens": screens,
                        "final_labels": [n["label"] for n in nodes]},
                       ensure_ascii=False, indent=2), encoding="utf-8"
        )
    except Exception as exc:
        (out / "failure.json").write_text(
            json.dumps({"screens": screens, "error": str(exc)},
                       ensure_ascii=False, indent=2), encoding="utf-8"
        )
        raise


if __name__ == "__main__":
    main()
