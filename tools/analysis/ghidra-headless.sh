#!/usr/bin/env bash
# Ghidra headless：为原生文件生成逐函数 C 伪代码与失败证据。
set -euo pipefail

usage() {
  cat <<'HELP'
用法：bash tools/analysis/ghidra-headless.sh --input BINARY --output DIRECTORY
需要：Ghidra 12.2 和 JDK 25；推荐使用 111 reverse 档离线安装。
产物：decompiled.c、functions.tsv、summary.json、input.sha256、ghidra.log。
环境变量：DEV111_TOOLS_ROOT、GHIDRA_HOME、JAVA_HOME、GHIDRA_ANALYSIS_TIMEOUT_SECONDS。
HELP
}
INPUT=''
OUTPUT=''
while [ "$#" -gt 0 ]; do
  case "$1" in
    --help|-h) usage; exit 0 ;;
    --input) [ "$#" -ge 2 ] || { usage >&2; exit 2; }; INPUT="$2"; shift 2 ;;
    --output) [ "$#" -ge 2 ] || { usage >&2; exit 2; }; OUTPUT="$2"; shift 2 ;;
    *) echo "未知选项：$1" >&2; usage >&2; exit 2 ;;
  esac
done
[ -n "$INPUT" ] && [ -n "$OUTPUT" ] || { usage >&2; exit 2; }
[ -f "$INPUT" ] || { echo "源文件不存在：$INPUT" >&2; exit 1; }
INPUT="$(realpath "$INPUT")"
mkdir -p "$OUTPUT"
OUTPUT="$(cd "$OUTPUT" && pwd -P)"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOLS_ROOT="${DEV111_TOOLS_ROOT:-$HOME/.local/share/111-dev}"
GHIDRA_HOME="${GHIDRA_HOME:-$TOOLS_ROOT/ghidra}"
JAVA_HOME="${JAVA_HOME:-$TOOLS_ROOT/jdk}"
[ -x "$GHIDRA_HOME/support/analyzeHeadless" ] || {
  echo "未找到 Ghidra analyzeHeadless；先执行 reverse 档 bootstrap" >&2; exit 1;
}
[ -x "$JAVA_HOME/bin/java" ] || { echo "未找到 JDK 25：$JAVA_HOME" >&2; exit 1; }
"$JAVA_HOME/bin/java" -version 2>&1 | grep -Eq 'version "25([."]|\+)' || {
  echo "Ghidra 12.2 要求 JDK 25" >&2; exit 1;
}
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/111-ghidra.XXXXXXXX")"
trap 'rm -rf "$WORK"' EXIT
sha256sum "$INPUT" > "$OUTPUT/input.sha256"
"$GHIDRA_HOME/support/analyzeHeadless" "$WORK" NativeSourceRecovery \
  -import "$INPUT" \
  -analysisTimeoutPerFile "${GHIDRA_ANALYSIS_TIMEOUT_SECONDS:-900}" \
  -scriptPath "$SCRIPT_DIR/ghidra-scripts" \
  -postScript ExportDecompiledC.java "$OUTPUT" \
  -log "$OUTPUT/ghidra.log" \
  -deleteProject
test -s "$OUTPUT/summary.json"
test -s "$OUTPUT/decompiled.c"
test -s "$OUTPUT/functions.tsv"
python3 - "$OUTPUT/summary.json" <<'PY'
import json, sys
from pathlib import Path
data = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
assert data["total_functions"] == data["decompiled_functions"] + data["failed_functions"]
assert data["decompiled_functions"] > 0, "没有成功反编译的函数"
print(json.dumps(data, ensure_ascii=False))
PY
