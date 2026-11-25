#!/usr/bin/env python3
"""Report reviewed upstream contract changes and fail closed in Android CI.

Git tree comparison works with sparse checkout. The import workflow examines its
staged candidate before creating a draft. Never refresh the lock automatically.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
REVIEW_PATHS = (
    "upstream/codex/codex-rs/app-server-protocol",
    "upstream/codex/codex-rs/config/src/config_toml.rs",
    "upstream/codex/codex-rs/protocol/src/hooks.rs",
    "upstream/codex/codex-rs/protocol/src/protocol.rs",
    "upstream/codex/codex-rs/protocol/src/openai_models.rs",
)


def git(root, *args, check=True):
    result = subprocess.run(["git", "-C", str(root), *args], text=True,
                            capture_output=True, encoding="utf-8", errors="replace")
    if check and result.returncode:
        raise RuntimeError(result.stderr.strip() or "Git 对象读取失败")
    return result


def changes(root, candidate, expected):
    if set(expected) != set(REVIEW_PATHS):
        raise ValueError("协议审查范围缺失或多出路径")
    git(root, "rev-parse", "--verify", candidate + "^{tree}")
    output = []
    for path in REVIEW_PATHS:
        old = expected[path]
        if not isinstance(old, str) or not re.fullmatch(r"[a-f0-9]{40}", old):
            raise ValueError("审查锁中的 Git 对象无效：" + path)
        result = git(root, "rev-parse", "--verify", candidate + ":" + path, check=False)
        new = result.stdout.strip() if result.returncode == 0 else None
        if new != old:
            output.append((path, old, new))
    return output


def describe(root, path, old, new):
    print(f"协议待审查：{path}\n  原对象 {old}\n  新对象 {new or '已删除'}")
    if not new:
        return
    if git(root, "cat-file", "-e", old, check=False).returncode:
        print("  浅克隆缺少历史对象；核对导入日志，或拉取审查候选历史后重跑。")
        return
    diff = git(root, "diff", "--no-ext-diff", "--no-textconv", "--stat", old, new).stdout
    print(diff[:12000])
    diff = git(root, "diff", "--no-ext-diff", "--no-textconv", "--unified=0", old, new).stdout
    print(diff[:80000])
    if len(diff) > 80000:
        print("  差异超过日志显示上限，完整核对：git diff --no-ext-diff", old, new)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", type=Path, default=ROOT)
    parser.add_argument("--candidate", default="HEAD", help="Git 提交或候选树")
    parser.add_argument("--report-only", action="store_true", help="创建审核草稿前仅报告差异")
    args = parser.parse_args()
    try:
        lock = json.loads((args.repository / "android-app/tools/upstream_protocol_review.json").read_text(encoding="utf-8"))
        if not lock.get("decisions") or not lock.get("reviewedBaseline") or not lock.get("reviewRecord"):
            raise ValueError("审查记录缺少业务判定或证据")
        if not (args.repository / lock["reviewRecord"]).is_file():
            raise ValueError("审查证据文件不存在")
        pending = changes(args.repository, args.candidate, lock["roots"])
        for path, old, new in pending:
            describe(args.repository, path, old, new)
        if pending:
            print("必须审查新增、删除、修改字段及通知、UI 组件、后台责任和真实验收，更新审查记录后才能通过。")
            return 0 if args.report_only else 1
        print("官方协议与相关类型的 Git 对象均与已审查候选一致")
        return 0
    except (OSError, ValueError, KeyError, RuntimeError) as error:
        print("协议差异审查失败：" + str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
