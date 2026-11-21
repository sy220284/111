#!/usr/bin/env bash
# Produce a deterministic, real HDiffPatch window+zstd test fixture for Android JNI.
# Mirrors the signed release's -WD, zstd and xxh128 patch generation flags.
set -euo pipefail

VERSION=5.1.3
ARCHIVE="hdiffpatch_v${VERSION}_bin_linux64.zip"
SHA256="628963bf2ee9108a97260fa5eef44acd9ec94369b76090a957c9182b3abbb558"
URL="https://github.com/sisong/HDiffPatch/releases/download/v${VERSION}/${ARCHIVE}"
ASSET_DIR="android-app/app/src/androidTest/assets"
mkdir -p "$ASSET_DIR"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
curl --fail --location --retry 3 --retry-delay 2 "$URL" -o "$TMP/$ARCHIVE"
printf '%s  %s\n' "$SHA256" "$TMP/$ARCHIVE" | sha256sum -c -
unzip -q "$TMP/$ARCHIVE" -d "$TMP/bin"
HDIFFZ="$(find "$TMP/bin" -type f -name hdiffz -print -quit)"
HPATCHZ="$(find "$TMP/bin" -type f -name hpatchz -print -quit)"
test -n "$HDIFFZ" && test -n "$HPATCHZ"
chmod +x "$HDIFFZ" "$HPATCHZ"

python3 - "$ASSET_DIR" <<'PY'
from pathlib import Path
import sys
root = Path(sys.argv[1])
old = (b"codex-111-android-native-update-audit|session|plan|terminal\n" * 4000)
new = old[:85000] + b"v2-new-settings-update-entry-and-integrity\n" + old[85000:]
(root / "update-old.bin").write_bytes(old)
(root / "update-new.bin").write_bytes(new)
PY
"$HDIFFZ" -s-64 -WD -w-2m -f -p-4 -c-zstd-19-24 -C-xxh128 \
  "$ASSET_DIR/update-old.bin" "$ASSET_DIR/update-new.bin" \
  "$ASSET_DIR/update-delta.hpatch"
"$HPATCHZ" -f "$ASSET_DIR/update-old.bin" "$ASSET_DIR/update-delta.hpatch" "$TMP/restored.bin"
cmp "$ASSET_DIR/update-new.bin" "$TMP/restored.bin"
echo "HDiffPatch v$VERSION 真实增量样本已生成，并通过 Linux 回放核验"
