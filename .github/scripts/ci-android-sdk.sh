#!/usr/bin/env bash
# Hosted CI only. Never download a user toolchain bundle.
set -euo pipefail
MODE="${1:?build/native/v8/emulator/build-emulator}"
SDK="$(realpath "${ANDROID_HOME:-${ANDROID_SDK_ROOT:?GitHub SDK missing}}")"
MANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$MANAGER" ]; then
  MANAGER="$(find "$SDK/cmdline-tools" -type f -path '*/bin/sdkmanager' | sort -V | tail -n 1)"
fi
test -x "$MANAGER"
NDK=27.2.12479018
packages=()
need() { if [ ! -s "$SDK/$2" ]; then packages+=("$1"); fi; }
case "$MODE" in build|native|v8|emulator|build-emulator) ;; *) echo "Unknown CI Android mode: $MODE" >&2; exit 2;; esac
if [[ "$MODE" == build || "$MODE" == build-emulator ]]; then
    need 'platforms;android-35' 'platforms/android-35/android.jar'
    need 'build-tools;35.0.0' 'build-tools/35.0.0/aapt'
fi
if [ "$MODE" != emulator ]; then
    need "ndk;$NDK" "ndk/$NDK/source.properties"
fi
if [ "$MODE" = v8 ]; then
    need 'platforms;android-36' 'platforms/android-36/android.jar'
    need 'build-tools;36.0.0' 'build-tools/36.0.0/aapt'
fi
if [[ "$MODE" == emulator || "$MODE" == build-emulator ]]; then
    readarray -t matrix < <(python3 - <<'PY'
import json
p=json.load(open("android-app/tools/device-matrix.json"))
print(p["system_image_api"])
print(p["target"])
print(p["arch"])
PY
)
    IMAGE="android-${matrix[0]}/${matrix[1]}/${matrix[2]}"
    need 'cmdline-tools;22.0' 'cmdline-tools/22.0/source.properties'
    need 'emulator' 'emulator/emulator'
    need 'platform-tools' 'platform-tools/adb'
    need "system-images;android-${matrix[0]};${matrix[1]};${matrix[2]}" "system-images/$IMAGE/system.img"
fi
if [ "${#packages[@]}" -gt 0 ]; then
  printf 'Install missing official SDK packages: %s\n' "${packages[@]}"
  "$MANAGER" --sdk_root="$SDK" --install "${packages[@]}"
else
  echo "All required SDK packages are already installed."
fi
printf 'ANDROID_HOME=%s\nANDROID_SDK_ROOT=%s\n' "$SDK" "$SDK" >> "$GITHUB_ENV"
if [[ "$MODE" == emulator || "$MODE" == build-emulator ]]; then
  test -x "$SDK/emulator/emulator"
  test -x "$SDK/platform-tools/adb"
  test -x "$SDK/cmdline-tools/22.0/bin/avdmanager"
  grep -F "system-images;android-${matrix[0]};${matrix[1]};${matrix[2]}" "$SDK/system-images/$IMAGE/package.xml"
  printf 'CODEX_EMULATOR_SDK=%s\n' "$SDK" >> "$GITHUB_ENV"
fi
if [ "$MODE" != emulator ]; then
  grep -Eq '^Pkg\.Revision[[:space:]]*=[[:space:]]*27\.2\.12479018[[:space:]]*$' "$SDK/ndk/$NDK/source.properties"
  test -x "$SDK/ndk/$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
  printf 'ANDROID_NDK_HOME=%s\n' "$SDK/ndk/$NDK" >> "$GITHUB_ENV"
fi
