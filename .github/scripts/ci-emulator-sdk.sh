#!/usr/bin/env bash
# Cache only the pinned system-image directory, never a whole SDK or AVD.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SDK="$(realpath "${ANDROID_HOME:-${ANDROID_SDK_ROOT:?GitHub SDK missing}}")"
readarray -t image < <(PYTHONPATH="$ROOT/tools/build" python3 - "$SDK" "$ROOT" <<'PY'
import json
from pathlib import Path
import sys
from platform_toolchain import ANDROID, android_available, android_path

sdk, root = map(Path, sys.argv[1:])
matrix = json.loads((root / "android-app/tools/device-matrix.json").read_text())
expected = f"system-images;android-{matrix['system_image_api']};{matrix['target']};{matrix['arch']}"
record = ANDROID["image"]
if record["package"] != expected or ANDROID["cmdline"]["version"] != matrix["command_line_tools"]:
    raise SystemExit("Pinned SDK packages and device matrix disagree")
path = android_path(sdk, "image")
print(path)
print(record["sha256"])
print(str(android_available(path, record)).lower())
PY
)
test "${#image[@]}" -eq 3
case "${1:?locate/install/seal/verify-cache}" in
  locate)
    printf 'path=%s\nkey=%s\npresent=%s\n' "${image[@]}" >> "$GITHUB_OUTPUT"
    ;;
  install)
    python3 "$ROOT/tools/build/platform_toolchain.py" sdk "$SDK" emulator
    printf 'ANDROID_HOME=%s\nANDROID_SDK_ROOT=%s\nCODEX_EMULATOR_SDK=%s\n' "$SDK" "$SDK" "$SDK" >> "$GITHUB_ENV"
    ;;
  seal)
    test "${image[2]}" = true
    cd "${image[0]}"
    find . -type f ! -name .111-image-sha256 -print0 | sort -z | xargs -0 sha256sum > .111-image-sha256
    test -s .111-image-sha256
    ;;
  verify-cache)
    test "${image[2]}" = true
    cd "${image[0]}"
    test -s .111-image-sha256
    sha256sum -c .111-image-sha256 --quiet
    ;;
  *) exit 2 ;;
esac
