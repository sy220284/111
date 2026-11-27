#!/usr/bin/env bash
# Hosted CI only. Never download a user toolchain bundle.
set -euo pipefail
MODE="${1:?build/native/v8}"
SDK="$(realpath "${ANDROID_HOME:-${ANDROID_SDK_ROOT:?GitHub SDK missing}}")"
MANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$MANAGER" ]; then
  MANAGER="$(find "$SDK/cmdline-tools" -type f -path '*/bin/sdkmanager' | sort -V | tail -n 1)"
fi
test -x "$MANAGER"
NDK=30.0.16248370
packages=()
need() { if [ ! -s "$SDK/$2" ]; then packages+=("$1"); fi; }
case "$MODE" in build|native|v8) ;; *) echo "Unknown CI Android mode: $MODE" >&2; exit 2;; esac
if [ "$MODE" = build ]; then
    need 'platforms;android-37.0' 'platforms/android-37.0/android.jar'
    need 'build-tools;37.0.0' 'build-tools/37.0.0/aapt'
fi
need "ndk;$NDK" "ndk/$NDK/source.properties"
if [ "$MODE" = v8 ]; then
    need 'platforms;android-36' 'platforms/android-36/android.jar'
    need 'build-tools;36.0.0' 'build-tools/36.0.0/aapt'
fi
if [ "${#packages[@]}" -gt 0 ]; then
  printf 'Install missing official SDK packages: %s\n' "${packages[@]}"
  "$MANAGER" --sdk_root="$SDK" --install "${packages[@]}"
else
  echo "All required SDK packages are already installed."
fi
printf 'ANDROID_HOME=%s\nANDROID_SDK_ROOT=%s\n' "$SDK" "$SDK" >> "$GITHUB_ENV"
revision="$(sed -nE 's/^Pkg\.Revision[[:space:]]*=[[:space:]]*([^[:space:]]+)[[:space:]]*$/\1/p' "$SDK/ndk/$NDK/source.properties")"
test "$revision" = "$NDK"
test -x "$SDK/ndk/$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
printf 'ANDROID_NDK_HOME=%s\n' "$SDK/ndk/$NDK" >> "$GITHUB_ENV"
