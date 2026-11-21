#!/usr/bin/env bash
# Retrieve the official Android HDiffPatch JNI library (v5.1.3) with a pinned SHA-256.
# Used by both verified debug APK and signed ARM64 release builds.
set -euo pipefail

SDK_VERSION="5.1.3"
ZIP_NAME="hdiffpatch_v${SDK_VERSION}_sdk_android_hpatchz.zip"
ZIP_SHA256="d7b98bcd3efb05436e08bcf525136c81e7dc6713bc58260770a2793818167fb7"
URL="https://github.com/sisong/HDiffPatch/releases/download/v${SDK_VERSION}/${ZIP_NAME}"
DIR="$(mktemp -d)"
trap 'rm -rf "$DIR"' EXIT
curl --fail --location --retry 3 --retry-delay 2 "$URL" -o "$DIR/$ZIP_NAME"
printf '%s  %s\n' "$ZIP_SHA256" "$DIR/$ZIP_NAME" | sha256sum -c -
unzip -q "$DIR/$ZIP_NAME" -d "$DIR/unpacked"

if [ "$#" -eq 0 ]; then
  echo "必须明确传入目标 ABI (arm64-v8a / x86_64)" >&2
  exit 1
fi
for ABI in "$@"; do
  case "$ABI" in
    arm64-v8a) ARCH="AArch64" ;;
    x86_64) ARCH="X86-64" ;;
    *) echo "不支持的更新器 ABI: $ABI" >&2; exit 1 ;;
  esac
  mapfile -t MATCHES < <(find "$DIR/unpacked" -type f -path "*/libs/$ABI/libhpatchz.so")
  if [ "${#MATCHES[@]}" -ne 1 ]; then
    echo "官方 SDK 缺少唯一的 $ABI libhpatchz.so" >&2
    exit 1
  fi
  SRC="${MATCHES[0]}"
  readelf -h "$SRC" | grep -Fq "$ARCH"
  readelf -Ws "$SRC" | grep -Fq "Java_com_github_sisong_HPatch_patch"
  mapfile -t ALIGNMENTS < <(readelf -Wl "$SRC" | awk '$1=="LOAD" {print $NF}')
  test "${#ALIGNMENTS[@]}" -gt 0
  for ALIGN in "${ALIGNMENTS[@]}"; do
    if (( ALIGN < 16384 )); then
      echo "$ABI libhpatchz.so 不符合 16 KB 页面对齐要求" >&2
      exit 1
    fi
  done
  DEST="android-app/app/src/main/jniLibs/$ABI"
  mkdir -p "$DEST"
  cp "$SRC" "$DEST/libhpatchz.so"
  echo "已核验并装配 $ABI HDiffPatch v$SDK_VERSION"
done
