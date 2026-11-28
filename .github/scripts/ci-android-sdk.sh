#!/usr/bin/env bash
# Official SDK distributions are pinned independently of the runner image.
set -euo pipefail
MODE="${1:?build/native/v8}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SDK="$(realpath "${ANDROID_HOME:-${ANDROID_SDK_ROOT:?GitHub SDK missing}}")"
python3 "$ROOT/tools/build/platform_toolchain.py" sdk "$SDK" "$MODE"
NDK="$(PYTHONPATH="$ROOT/tools/build" python3 -c 'import platform_toolchain as p; print(p.ANDROID["ndk"]["version"])')"
printf 'ANDROID_HOME=%s\nANDROID_SDK_ROOT=%s\nANDROID_NDK_HOME=%s\n' "$SDK" "$SDK" "$SDK/ndk/$NDK" >> "$GITHUB_ENV"
