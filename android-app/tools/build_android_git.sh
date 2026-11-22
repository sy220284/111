#!/usr/bin/env bash
# Build the original Git CLI for Android Bionic. Git remains an external executable
# called through the Codex process's PATH, preserving upstream CLI behavior.
set -euo pipefail
if [ "$#" -ne 4 ]; then
  echo "Usage: $0 <target-triple> <ndk-bin> <clang> <destination>" >&2
  exit 2
fi
TARGET="$1"; BIN="$2"; CLANG="$3"; DEST="$4"
case "$TARGET" in aarch64-linux-android|x86_64-linux-android) ;; *)
  echo "Unsupported Android Git target: $TARGET" >&2; exit 2 ;;
esac
CC="$BIN/$CLANG"
AR="$BIN/llvm-ar"; RANLIB="$BIN/llvm-ranlib"
for TOOL in "$CC" "$AR" "$RANLIB"; do test -x "$TOOL"; done
VERSION="v2.52.0"
COMMIT="9a2fb147f2c61d0cab52c883e7e26f5b7948e3ed"
SOURCE="$RUNNER_TEMP/git-android-$TARGET"
git clone --depth 1 --branch "$VERSION" --single-branch \
  https://github.com/git/git.git "$SOURCE"
actual="$(git -C "$SOURCE" rev-parse HEAD)"
if [ "$actual" != "$COMMIT" ]; then
  echo "Pinned Git source mismatch: expected $COMMIT, got $actual" >&2
  exit 1
fi
# Local Git operations run under the Android app UID. Keep required zlib,
# but do not add unverified bundled curl/OpenSSL dynamic libraries.
make -C "$SOURCE" -j 2 git \
  "CC=$CC" "AR=$AR" "RANLIB=$RANLIB" \
  "SHELL_PATH=/bin/sh" 'SHELL_PATH_CQ_SQ="/system/bin/sh"' \
  "DEFAULT_EDITOR=vi" "DEFAULT_PAGER=cat" \
  "CFLAGS=-O2 -fPIC" \
  "LDFLAGS=-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384" \
  NO_CURL=YesPlease NO_EXPAT=YesPlease NO_GETTEXT=YesPlease \
  NO_ICONV=YesPlease NO_OPENSSL=YesPlease \
  NO_TCLTK=YesPlease NO_PERL=YesPlease NO_NSEC=YesPlease \
  NO_INSTALL_HARDLINKS=YesPlease
test -s "$SOURCE/git"
mkdir -p "$(dirname "$DEST")"
cp "$SOURCE/git" "$DEST"
chmod 755 "$DEST"
test -s "$DEST"
"$BIN/llvm-readelf" -h "$DEST"
echo "Android local Git $VERSION built from commit $COMMIT for $TARGET"
