#!/usr/bin/env python3
"""Validate that every registered feature and audited subpage uses its declared component."""
import argparse
import json
import re
from pathlib import Path
from ui_evidence_manifest import load_evidence_manifest, declared_screens

SOURCE = "android-app/app/src/main/java/io/github/sy220284/codexandroid"
TOOLS = "android-app/tools"


def check(root):
    root = Path(root)
    source = root / SOURCE
    registry = json.loads((root / TOOLS / "ui_interaction_coverage.json").read_text())
    expected = set(re.findall(r'UiFeature\("([^"]+)"', (source / "UiFeatureCatalog.kt").read_text()))
    routes = registry["routes"]
    subpages = registry["subpages"]
    errors = []
    actual = [row["route"] for row in routes]
    if set(actual) != expected or len(actual) != len(set(actual)):
        errors.append(f"功能入口遗漏、重复或过期: missing={sorted(expected-set(actual))}, extra={sorted(set(actual)-expected)}")
    children = [row["id"] for row in subpages]
    if len(children) != len(set(children)):
        errors.append("子页面标识重复")
    tests = "\n".join(p.read_text() for p in (root / "android-app/app/src").glob("**/*Test.kt"))
    screenshots = set(declared_screens(load_evidence_manifest(root)))
    for row in routes + subpages:
        label = row.get("route", row.get("id"))
        if "parent" in row and row["parent"] not in expected:
            errors.append(f"{label}: 子页面归属无效")
        path = source / row["file"]
        if not path.is_file():
            errors.append(f"{label}: 源文件不存在")
            continue
        code = path.read_text()
        owner = re.search(r"(?m)^(?:private |internal |public )?fun\s+" + re.escape(row["owner"]) + r"\s*\(", code)
        if owner is None:
            errors.append(f"{label}: 页面函数不存在")
            continue
        body = code[owner.end():]
        following = re.search(r"(?m)^(?:private |internal |public )?fun\s+\w+\s*\(", body)
        if following:
            body = body[:following.start()]
        if not re.search(r"\b" + re.escape(row["component"]) + r"\s*\(", body):
            errors.append(f"{label}: 页面内未调用 {row['component']}")
        if row.get("test") and not re.search(r"\bfun\s+" + re.escape(row["test"]) + r"\s*\(", tests):
            errors.append(f"{label}: 回归用例不存在")
        if row.get("screenshot") and row["screenshot"] not in screenshots:
            errors.append(f"{label}: 截图未登记")
    return {"routes": len(routes), "subpages": len(subpages), "errors": errors,
            "scope": "静态组件调用与证据声明；不等同设备测试通过"}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", default=".")
    parser.add_argument("--report", default="interaction-coverage.json")
    args = parser.parse_args()
    result = check(args.repository)
    Path(args.report).write_text(json.dumps(result, ensure_ascii=False, indent=2)+"\n")
    print(json.dumps(result, ensure_ascii=False))
    raise SystemExit(bool(result["errors"]))
