#!/usr/bin/env python3
"""Production controls must use the shared visual and interaction implementation."""
import argparse
import json
import re
from pathlib import Path

RAW_OWNERS = {
    "Button": {"VisualSystem"},
    "OutlinedButton": {"VisualSystem"},
    "TextButton": {"CodexControlPrimitives"},
    "IconButton": {"CodexControlPrimitives"},
    "OutlinedTextField": {"CodexControlPrimitives"},
    "TextField": {"CodexControlPrimitives"},
    "FilterChip": {"CodexControlPrimitives"},
    "AssistChip": {"CodexControlPrimitives"},
    "RadioButton": {"CodexControlPrimitives"},
    "Checkbox": {"CodexControlPrimitives"},
    "Switch": {"VisualSystem"},
    "CircularProgressIndicator": {"CodexControlPrimitives"},
    "LinearProgressIndicator": {"AtlasInteractionComponents"},
    "SegmentedButton": {"AtlasInteractionComponents"},
    "SingleChoiceSegmentedButtonRow": {"AtlasInteractionComponents"},
}
SHARED = {
    "CodexPrimaryButton": "VisualSystem", "CodexSecondaryButton": "VisualSystem",
    "WhiteSwitch": "VisualSystem", "CodexTextAction": "CodexControlPrimitives",
    "CodexIconAction": "CodexControlPrimitives", "CodexInputField": "CodexControlPrimitives",
    "CodexFilterChip": "CodexControlPrimitives", "CodexCheckBox": "CodexControlPrimitives",
    "CodexRadio": "CodexControlPrimitives", "CodexActivityIndicator": "CodexControlPrimitives",
    "AtlasExclusiveChip": "CodexControlPrimitives", "CodexSuggestionChip": "CodexControlPrimitives",
    "AtlasFormField": "AtlasFormField", "AtlasSearchField": "AtlasInteractionComponents",
    "AtlasChoiceRow": "AtlasInteractionComponents", "AtlasCompactChoice": "AtlasInteractionComponents",
    "AtlasOperationProgress": "AtlasInteractionComponents",
}

def without_comments_and_literals(source):
    # Retain newlines for source evidence. Kotlin interpolation is deliberately not used
    # to call composables in this codebase.
    pattern = r'""".*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|/\*.*?\*/|//[^\n]*'
    return re.sub(pattern, lambda m: re.sub(r"[^\n]", " ", m.group()), source, flags=re.S)

def audit(sources):
    errors = []
    calls = {name: [] for name in SHARED}
    raw = {name: [] for name in RAW_OWNERS}
    for file, source in sorted(sources.items()):
        code = without_comments_and_literals(source)
        # Window size and effective typography have one source of truth. Do not allow
        # individual pages to restore nominal-display or system-font-only assumptions.
        if file != "CodexViewport":
            for hit in re.finditer(r"\b(?:screenWidthDp|screenHeightDp)\b|\bLocalDensity\s*\.\s*current\s*\.\s*fontScale\b", code):
                line = code.count("\n", 0, hit.start()) + 1
                errors.append(f"{file}:{line}: {hit.group()} 必须通过共享窗口与组合字号策略")
        if file != "CodexUiAsyncState":
            for hit in re.finditer(r"\bproduceState\s*(?:<[^\n]*?>)?\s*\(", code):
                line = code.count("\n", 0, hit.start()) + 1
                errors.append(f"{file}:{line}: 异步界面状态必须使用 produceCodexUiState")
        # Remove declarations; extension and ordinary declarations are treated equally.
        code = re.sub(r"\bfun\s+(?:[\w.]+\.)?(\w+)\s*\(", lambda m: " " * len(m.group()), code)
        for name in list(RAW_OWNERS) + list(SHARED):
            for hit in re.finditer(r"\b" + name + r"\s*\(", code):
                line = code.count("\n", 0, hit.start()) + 1
                location = {"file": file, "line": line}
                if name in RAW_OWNERS:
                    raw[name].append(location)
                    if file not in RAW_OWNERS[name]:
                        errors.append(f"{file}:{line}: {name} 绕开共享控件")
                else:
                    calls[name].append(location)
        if re.search(r"\bfun\s+WelcomeActionCard\s*\(", source):
            errors.append(f"{file}: 旧欢迎卡片已退役")
    for name, owner in SHARED.items():
        if not any(hit["file"] != owner for hit in calls[name]):
            errors.append(f"{name}: 缺少其他生产文件调用")
    return {"status": "failed" if errors else "source_verified",
            "source_files": len(sources), "raw_owners": raw, "shared_calls": calls, "errors": errors}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", default=".")
    parser.add_argument("--report", default="component-system-audit.json")
    args = parser.parse_args()
    root = Path(args.repository) / "android-app/app/src/main/java"
    sources = {p.stem: p.read_text(encoding="utf-8") for p in root.rglob("*.kt")}
    result = audit(sources)
    Path(args.report).write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"共享控件检查：{result['source_files']} 个源码文件；{sum(len(v) for v in result['shared_calls'].values())} 处共享调用；{len(result['errors'])} 个问题")
    for error in result["errors"]:
        print(error)
    return bool(result["errors"])

if __name__ == "__main__":
    raise SystemExit(main())
