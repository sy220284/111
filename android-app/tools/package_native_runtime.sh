#!/usr/bin/env bash
# Verify and install both Android-native Codex executables from one trusted build.
set -euo pipefail
if [ "$#" -ne 3 ]; then
  echo "Usage: $0 <arm64-v8a|x86_64> <artifact-dir> <llvm-strip>" >&2
  exit 2
fi
ABI="$1"; SRC_DIR="$2"; STRIP="$3"
case "$ABI" in
  arm64-v8a) ARCH=AArch64 ;;
  x86_64) ARCH=X86-64 ;;
  *) echo "Unsupported Android ABI: $ABI" >&2; exit 2 ;;
esac
test -x "$STRIP"
TARGET="android-app/app/src/main/jniLibs/$ABI"
mkdir -p "$TARGET"
for ENTRY in "codex-app-server:libcodex_server.so" "codex-code-mode-host:libcodex_code_mode_host.so"; do
  NAME="${ENTRY%%:*}"; LIB="${ENTRY#*:}"
  mapfile -t MATCHES < <(find "$SRC_DIR" -type f -name "$NAME")
  if [ "${#MATCHES[@]}" -ne 1 ]; then
    echo "::error::$ABI expected one $NAME, found ${#MATCHES[@]}" >&2
    exit 1
  fi
  SRC="${MATCHES[0]}"
  test -s "$SRC"
  readelf -h "$SRC" | grep -Fq "$ARCH"
  readelf -l "$SRC" | grep -Fq '/system/bin/linker64'
  mapfile -t ALIGNS < <(readelf -Wl "$SRC" | awk '$1=="LOAD" {print $NF}')
  if [ "${#ALIGNS[@]}" -eq 0 ]; then
    echo "::error::$ABI $NAME missing LOAD segments" >&2; exit 1
  fi
  for ALIGN in "${ALIGNS[@]}"; do
    if (( ALIGN < 16384 )); then
      echo "::error::$ABI $NAME has ELF page alignment below 16 KB: $ALIGN" >&2
      exit 1
    fi
  done
  cp "$SRC" "$TARGET/$LIB"
  chmod 755 "$TARGET/$LIB"
  "$STRIP" --strip-all "$TARGET/$LIB"
  readelf -h "$TARGET/$LIB" | grep -Fq "$ARCH"
  readelf -l "$TARGET/$LIB" | grep -Fq '/system/bin/linker64'
  echo "$ABI $NAME installed as $LIB"
  sha256sum "$TARGET/$LIB"
done
