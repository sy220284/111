#!/usr/bin/env bash
# Hosted runner: no emulator bundle, AVD, or whole SDK is cached.
set -euo pipefail
SDK="$(realpath "${ANDROID_HOME:-${ANDROID_SDK_ROOT:?GitHub SDK missing}}")"
readarray -t matrix < <(python3 - <<'PY'
import json
p=json.load(open("android-app/tools/device-matrix.json"))
print(p["system_image_api"])
print(p["target"])
print(p["arch"])
print(p["command_line_tools"])
PY
)
test "${#matrix[@]}" -eq 4
IMAGE="android-${matrix[0]}/${matrix[1]}/${matrix[2]}"
PACKAGE="system-images;android-${matrix[0]};${matrix[1]};${matrix[2]}"
TOOLS="${matrix[3]}"
image_present() {
  [ -s "$SDK/system-images/$IMAGE/system.img" ] &&
  [ -s "$SDK/system-images/$IMAGE/package.xml" ] &&
  grep -Fq "$PACKAGE" "$SDK/system-images/$IMAGE/package.xml"
}
case "${1:?locate/install}" in
  locate)
    present=false
    if image_present; then present=true; fi
    printf 'path=%s\nkey=%s-%s-%s\npresent=%s\n' "$SDK/system-images/$IMAGE" "${matrix[0]}" "${matrix[1]}" "${matrix[2]}" "$present" >> "$GITHUB_OUTPUT"
    ;;
  install)
    MANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
    if [ ! -x "$MANAGER" ]; then
      MANAGER="$(find "$SDK/cmdline-tools" -type f -path '*/bin/sdkmanager' | sort -V | tail -n 1)"
    fi
    test -x "$MANAGER"
    packages=()
    if [ ! -x "$SDK/cmdline-tools/$TOOLS/bin/avdmanager" ]; then packages+=("cmdline-tools;$TOOLS"); fi
    if [ ! -x "$SDK/emulator/emulator" ]; then packages+=(emulator); fi
    if [ ! -x "$SDK/platform-tools/adb" ]; then packages+=(platform-tools); fi
    if ! image_present; then packages+=("$PACKAGE"); fi
    if [ "${#packages[@]}" -gt 0 ]; then
      "$MANAGER" --sdk_root="$SDK" --install "${packages[@]}"
    fi
    test -x "$SDK/emulator/emulator"
    test -x "$SDK/platform-tools/adb"
    test -x "$SDK/cmdline-tools/$TOOLS/bin/avdmanager"
    image_present
    printf 'ANDROID_HOME=%s\nANDROID_SDK_ROOT=%s\nCODEX_EMULATOR_SDK=%s\n' "$SDK" "$SDK" "$SDK" >> "$GITHUB_ENV"
    ;;
  *) exit 2 ;;
esac
