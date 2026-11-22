#!/usr/bin/env python3
"""Read-only preflight for an upstream Codex Android compatibility upgrade.

After cargo fetch --locked --target aarch64-linux-android, validate MXC's pinned
source and both Code Mode Host adaptations without modifying any tracked files
or Cargo checkouts, before the expensive native compilation starts.
"""
import argparse
import tempfile
from pathlib import Path

from prepare_android_mxc import AUDIT_CALL, OS_GUARD, find_source, fix
from prepare_android_code_mode import adapt_discovery, adapt_host


def rehearse(codex_rs: Path, cargo_home: Path) -> dict:
    codex_rs = codex_rs.resolve()
    cargo_home = cargo_home.expanduser().resolve()
    for relative in ("Cargo.toml", "Cargo.lock", "app-server/Cargo.toml"):
        if not (codex_rs / relative).is_file():
            raise RuntimeError(f"候选 Codex 缺少 {relative}：{codex_rs}")
    dispatch = find_source(cargo_home)
    original = dispatch.read_text(encoding="utf-8")
    with tempfile.TemporaryDirectory(prefix="codex-mxc-rehearsal-") as tmp:
        scratch = Path(tmp) / "dispatch.rs"
        scratch.write_text(original, encoding="utf-8")
        fix(scratch)
        patched = scratch.read_text(encoding="utf-8")
        fix(scratch)
        if scratch.read_text(encoding="utf-8") != patched:
            raise RuntimeError("MXC 兼容补丁重复应用后内容改变")
        lines = patched.splitlines()
        guarded = sum(
            line.strip() == AUDIT_CALL and index > 0 and
            lines[index - 1].strip() == OS_GUARD
            for index, line in enumerate(lines)
        )
        if guarded != 3:
            raise RuntimeError(f"MXC 关键审计点 Android 隔离缺失：{guarded}/3")
    if dispatch.read_text(encoding="utf-8") != original:
        raise RuntimeError("升级演练误修改 Cargo 源码缓存")

    checked = []
    for relative, adapter in (
        ("install-context/src/lib.rs", adapt_discovery),
        ("code-mode-host/src/main.rs", adapt_host),
    ):
        path = codex_rs / relative
        if not path.is_file():
            raise RuntimeError(f"候选 Code Mode Host 文件缺失：{path}")
        original = path.read_text(encoding="utf-8")
        patched = adapter(original)
        if adapter(patched) != patched:
            raise RuntimeError(f"兼容补丁重复应用不一致：{relative}")
        if path.read_text(encoding="utf-8") != original:
            raise RuntimeError(f"升级演练误修改候选源码：{relative}")
        checked.append(relative)
    return {"guarded_mxc_calls": guarded, "code_mode_sources": checked}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--codex-rs", type=Path, default=Path("upstream/codex/codex-rs"))
    parser.add_argument("--cargo-home", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = rehearse(args.codex_rs, args.cargo_home)
    except (OSError, RuntimeError, ValueError) as error:
        parser.exit(1, f"Android 上游升级预演失败：{error}\n")
    print("上游 Android 兼容预演通过：MXC 审计点",
          result["guarded_mxc_calls"], "/3，Code Mode Host 源码",
          ", ".join(result["code_mode_sources"]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
