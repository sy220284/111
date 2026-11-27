#!/usr/bin/env bash
# Consume the exact reviewed 777 package set; network access is limited to an explicit producer.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if [ "$#" -ne 3 ]; then echo "usage: $0 <arm64-v8a|x86_64> <runtime-cache> <output>" >&2; exit 2; fi
ABI="$1"; CACHE="$(realpath "$2")"; OUT="$(realpath -m "$3")"
case "$ABI" in arm64-v8a|x86_64) ;; *) exit 2;; esac
test "$OUT" != / && test "$OUT" != "$CACHE" && test ! -e "$OUT"
mkdir -p "$OUT"
export DSH_RUNTIME_ABIS="$ABI"
export DSH_RUNTIME_OFFLINE=true
for component in git node python; do
  test -d "$CACHE/termux-$component"
  TERMUX_RUNTIME_CACHE="$CACHE/termux-$component" bash "$ROOT/tools/runtime/prepare-termux-$component.sh" "$OUT/$component"
done
python3 "$ROOT/tools/runtime/runtime_versions.py" "$OUT" "$ABI"
