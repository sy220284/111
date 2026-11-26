#!/usr/bin/env bash
# Explicit online producer. APK assembly consumes its output with no network.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if [ "$#" -ne 2 ]; then echo "usage: $0 <arm64-v8a|x86_64> <output>" >&2; exit 2; fi
ABI="$1"; OUT="$(realpath -m "$2")"
case "$ABI" in arm64-v8a|x86_64) ;; *) exit 2;; esac
test ! -e "$OUT"
TEMP="$(mktemp -d)"
trap 'rm -rf "$TEMP"' EXIT
if [ "$ABI" = arm64-v8a ]; then
  python3 "$ROOT/tools/dev/ai-toolchain.py" --root "$TEMP/shared" shared-install --components runtime-cache --artifacts-dir "$TEMP/downloads" --download > "$TEMP/shared.json"
  cp -a "$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["runtime-cache"])' "$TEMP/shared.json")" "$TEMP/cache"
else
  mkdir -p "$TEMP/cache"
  for component in git node python; do
    DSH_RUNTIME_ABIS=x86_64 DSH_RUNTIME_OFFLINE=false TERMUX_RUNTIME_CACHE="$TEMP/cache/termux-$component" bash "$ROOT/tools/runtime/prepare-termux-$component.sh" "$TEMP/seed-$component"
  done
fi
bash "$ROOT/tools/runtime/prepare-111-runtime.sh" "$ABI" "$TEMP/cache" "$OUT"
python3 "$ROOT/android-app/tools/prepare_prebuilt_developer_runtime.py" "$ABI" --prepared-dir "$OUT" --prepare-source --git-source-output "$OUT/git-corresponding-source.tar.xz"
