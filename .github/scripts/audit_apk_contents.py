#!/usr/bin/env python3
"""Audit APK size and required executable payloads without modifying the APK.

UI-only signing smoke deliberately omits the Codex core. Shipping APKs must
contain the complete ABI-specific native executables; slimming must never remove them.
"""
import argparse
import hashlib
import json
import sys
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
)


class ApkAuditError(ValueError):
    pass


def audit(apk: Path, abi: str, mode: str) -> dict:
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
            if mode == "full":
                required.extend(f"lib/{abi}/{name}" for name in
                                ("libnode.so", "libnpm.so", "libpython3.so", "libcodex_curl.so"))
                if abi == "arm64-v8a":
                    required.append(f"lib/{abi}/libcodex_git_remote_http.so")
                required.extend("assets/development-runtime/development-runtime." + ext
                                for ext in ("zip", "sha256", "version", "provenance.json"))
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
            bad_entry=archive.testzip()
            if bad_entry is not None:
                raise ApkAuditError("APK CRC 校验失败："+bad_entry)
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
                "sha256": sha256(apk),
                "apk_bytes": apk.stat().st_size,
                "file_count": len(entries),
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

def verify_report(apk,abi,mode,report):
    data=json.loads(report.read_text(encoding="utf-8"))
    if (data["abi"] != abi or data["mode"] != mode or data["apk_bytes"] != apk.stat().st_size
        or data["sha256"] != sha256(apk)):
        raise ApkAuditError("下载 APK 与完整审计凭证字节不一致")
    return data

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--abi", choices=sorted(MACHINES), required=True)
    parser.add_argument("--mode", choices=("ui-smoke", "full"), default="full")
    parser.add_argument("--report", type=Path)
    parser.add_argument("--verify-report",type=Path)
    args = parser.parse_args()
    try:
        result = verify_report(args.apk,args.abi,args.mode,args.verify_report) if args.verify_report else audit(args.apk,args.abi,args.mode)
    except (ApkAuditError, OSError, ValueError, KeyError) as error:
        print(f"APK 安全核验失败：{error}", file=sys.stderr)
        return 1
    report = json.dumps(result, ensure_ascii=False, indent=2)
    print(report)
    if args.report is not None:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(report + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
