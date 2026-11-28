#!/usr/bin/env python3
"""Audit APK size and required executable payloads without modifying the APK.

UI-only signing smoke deliberately omits the Codex core. Shipping APKs must
contain the complete ABI-specific native executables; slimming must never remove them.
"""
import argparse
import hashlib
import json
import sys
import shutil
import tempfile
from collections import defaultdict
from pathlib import Path
from zipfile import BadZipFile, ZipFile

MACHINES = {"arm64-v8a": 183, "x86_64": 62}
REQUIRED_ANDROID = ("AndroidManifest.xml", "resources.arsc", "classes.dex")
# All four ARM64 Codex executables are stored as native libraries for Android extraction.
REQUIRED_RUNTIME = (
    "libcodex_server.so",
    "libcodex_code_mode_host.so",
    "libcodex_rg.so",
    "libcodex_git.so",
    "libcodex_node.so", "libcodex_python.so", "libcodex_curl.so",
    "libcodex_jq.so", "libcodex_tool_launcher.so", "libcodex_git_remote_http.so",
)


class ApkAuditError(ValueError):
    pass


def inventory(archive):
    records = {}
    for item in archive.infolist():
        if item.is_dir():
            continue
        if item.filename in records:
            raise ApkAuditError("归档包含重名文件：" + item.filename)
        with archive.open(item) as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        records[item.filename] = {"bytes": item.file_size, "compressed": item.compress_size, "sha256": digest}
    return records


def runtime_inventory(archive, strict=False):
    prefix = "assets/development-runtime/development-runtime"
    if prefix + ".zip" not in archive.namelist():
        return None
    with tempfile.TemporaryFile() as temporary:
        with archive.open(prefix + ".zip") as source:
            shutil.copyfileobj(source, temporary, length=1024 * 1024)
        temporary.seek(0)
        with ZipFile(temporary) as inner:
            files = inventory(inner)
    logical = dict(files)
    if prefix + ".files.tsv" in archive.namelist():
        logical = {}
        canonical = {}
        for line in archive.read(prefix + ".files.tsv").decode("utf-8").splitlines():
            name, sha, raw_size = line.split("\t")
            size = int(raw_size)
            if name in logical or any(p in ("", ".", "..") for p in name.split("/")) or "\\" in name:
                raise ApkAuditError("运行环境清单路径异常")
            first = canonical.setdefault(sha, name)
            source = files.get(first)
            if source is None or source["sha256"] != sha or source["bytes"] != size:
                raise ApkAuditError("运行环境清单与归档内容不一致：" + name)
            logical[name] = {**source, "canonical": first}
        if set(canonical.values()) != set(files) or not logical or list(logical) != sorted(logical):
            raise ApkAuditError("运行环境归档未按内容去重")
    elif strict:
        raise ApkAuditError("运行环境缺少文件清单")
    return {"physical": files, "logical": logical,
            "logical_bytes": sum(x["bytes"] for x in logical.values()),
            "unique_bytes": sum(x["bytes"] for x in files.values()),
            "duplicate_groups": duplicate_groups(logical)}


def duplicate_groups(files):
    by_digest = defaultdict(list)
    for name, item in files.items():
        by_digest[item["sha256"]].append(name)
    return [{"paths": paths, "bytes_each": files[paths[0]]["bytes"]}
            for paths in by_digest.values() if len(paths) > 1]


def difference(old, new):
    return sorted(
        ({"path": name, "old": old.get(name), "new": new.get(name),
          "compressed_delta": new.get(name, {}).get("compressed", 0) - old.get(name, {}).get("compressed", 0)}
         for name in old.keys() | new.keys() if old.get(name) != new.get(name)),
        key=lambda row: (-abs(row["compressed_delta"]), row["path"]))


def audit(apk: Path, abi: str, mode: str, debug_executor: bool = False) -> dict:
    if abi not in MACHINES:
        raise ApkAuditError(f"未知目标 ABI：{abi}")
    if mode not in ("ui-smoke", "full"):
        raise ApkAuditError(f"未知验证模式：{mode}")
    try:
        with ZipFile(apk) as archive:
            entries = {item.filename: item for item in archive.infolist() if not item.is_dir()}
            if len(entries) != len([item for item in archive.infolist() if not item.is_dir()]):
                raise ApkAuditError("APK 包含重名文件条目")
            required = list(REQUIRED_ANDROID) + [f"lib/{abi}/libhpatchz.so"]
            if mode == "full":
                required.append("assets/domestic-providers.json")
                required.extend(f"lib/{abi}/{binary}" for binary in REQUIRED_RUNTIME)
                required.extend("assets/development-runtime/development-runtime." + ext
                                for ext in ("zip", "sha256", "version", "provenance.json", "commands.tsv", "files.tsv"))
            executor = f"lib/{abi}/libcodex_test_executor.so"
            if debug_executor:
                required.append(executor)
            elif any(name.endswith("/libcodex_test_executor.so") for name in entries):
                raise ApkAuditError("非完整调试 APK 混入测试专用执行器")
            missing = sorted(set(required) - entries.keys())
            if missing:
                raise ApkAuditError("关键安装文件缺失：" + ", ".join(missing))
            other_abis = sorted(
                entry for entry in entries
                if entry.startswith("lib/") and entry.count("/") >= 2
                and entry.split("/")[1] != abi
            )
            if other_abis:
                raise ApkAuditError("APK 混入非目标 ABI 原生库：" + ", ".join(other_abis[:8]))
            for name in sorted(entry for entry in entries if entry.startswith(f"lib/{abi}/") and entry.endswith(".so")):
                with archive.open(name) as stream:
                    header = stream.read(20)
                if len(header) < 20 or header[:4] != b"\x7fELF" or header[4] != 2 or header[5] not in (1, 2):
                    raise ApkAuditError(f"原生库格式无效（要求 ELF64）：{name}")
                byteorder = "little" if header[5] == 1 else "big"
                machine = int.from_bytes(header[18:20], byteorder)
                if machine != MACHINES[abi]:
                    raise ApkAuditError(f"原生库架构错误：{name}（machine={machine}）")
            files = inventory(archive)
            runtime = runtime_inventory(archive, strict=mode == "full")
            categories = {"dex": 0, "native": 0, "resources": 0, "assets": 0, "other": 0}
            compressed = {key: 0 for key in categories}
            for name, item in entries.items():
                key = ("dex" if name.endswith(".dex") else
                       "native" if name.startswith("lib/") else
                       "assets" if name.startswith("assets/") else
                       "resources" if name.startswith("res/") or name == "resources.arsc" else "other")
                categories[key] += item.file_size
                compressed[key] += item.compress_size
            return {
                "apk": str(apk), "abi": abi, "mode": mode,
                "debug_executor": debug_executor,
                "sha256": sha256(apk),
                "apk_bytes": apk.stat().st_size,
                "file_count": len(entries),
                "entries": files,
                "duplicate_groups": duplicate_groups(files),
                "development_runtime": runtime,
                "uncompressed_bytes": sum(item.file_size for item in entries.values()),
                "uncompressed_by_category": categories,
                "compressed_by_category": compressed,
                "core_bytes": entries.get(f"lib/{abi}/libcodex_server.so").file_size
                    if f"lib/{abi}/libcodex_server.so" in entries else None,
                "runtime_bytes": sum(entries[f"lib/{abi}/{binary}"].file_size
                    for binary in REQUIRED_RUNTIME) if mode == "full" else None,
            }
    except (BadZipFile, OSError) as error:
        raise ApkAuditError(f"APK 结构或文件读取失败：{error}") from error


def sha256(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source,"sha256").hexdigest()

def verify_report(apk,abi,mode,report,debug_executor=False):
    data=json.loads(report.read_text(encoding="utf-8"))
    if (data["abi"] != abi or data["mode"] != mode or data.get("debug_executor", False) != debug_executor
        or data["apk_bytes"] != apk.stat().st_size
        or data["sha256"] != sha256(apk)):
        raise ApkAuditError("下载 APK 与完整审计凭证字节不一致")
    return data

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--abi", choices=sorted(MACHINES), required=True)
    parser.add_argument("--mode", choices=("ui-smoke", "full"), default="full")
    parser.add_argument("--debug-executor", action="store_true", help="Require the standalone executor in a full debug APK; otherwise reject it")
    parser.add_argument("--report", type=Path)
    parser.add_argument("--previous-apk", type=Path)
    parser.add_argument("--verify-report",type=Path)
    args = parser.parse_args()
    try:
        result = (verify_report(args.apk, args.abi, args.mode, args.verify_report, args.debug_executor)
                  if args.verify_report else audit(args.apk, args.abi, args.mode, args.debug_executor))
        if args.previous_apk is not None:
            with ZipFile(args.previous_apk) as old:
                result["previous_apk_bytes"] = args.previous_apk.stat().st_size
                result["apk_delta_bytes"] = result["apk_bytes"] - result["previous_apk_bytes"]
                result["entry_changes"] = difference(inventory(old), result["entries"])
                previous_runtime = runtime_inventory(old)
                if previous_runtime is not None and result["development_runtime"] is not None:
                    result["runtime_changes"] = difference(previous_runtime["physical"], result["development_runtime"]["physical"])
    except (ApkAuditError, OSError, ValueError, KeyError, BadZipFile) as error:
        print(f"APK 安全核验失败：{error}", file=sys.stderr)
        return 1
    report = json.dumps(result, ensure_ascii=False, indent=2)
    summary = {k: v for k, v in result.items() if k not in
               {"entries", "duplicate_groups", "development_runtime", "entry_changes", "runtime_changes"}}
    summary["largest_entries"] = sorted(result["entries"].items(), key=lambda pair: -pair[1]["compressed"])[:15]
    if result["development_runtime"] is not None:
        runtime = result["development_runtime"]
        summary["development_runtime"] = {
            "logical_bytes": runtime["logical_bytes"], "unique_bytes": runtime["unique_bytes"],
            "duplicate_bytes_removed": runtime["logical_bytes"] - runtime["unique_bytes"],
            "largest_physical_entries": sorted(runtime["physical"].items(), key=lambda pair: -pair[1]["compressed"])[:15]}
    for key in ("entry_changes", "runtime_changes"):
        if key in result:
            summary[key] = result[key][:15]
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    if args.report is not None:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(report + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
