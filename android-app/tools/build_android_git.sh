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
# Git 2.52 uses POSIX thread cancellation around fork. Android Bionic has
# pthread_sigmask but intentionally does not expose pthread_setcancelstate.
# Keep Git's normal pthread support, and omit only unavailable cancellation
# calls for the pinned Android target. Verify every upstream pattern strictly.
python3 - "$SOURCE/run-command.c" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
source = path.read_text()
edits = (
    (
        "#ifndef NO_PTHREADS\n\tint cs;\n#endif",
        "#if !defined(NO_PTHREADS) && !defined(__ANDROID__)\n\tint cs;\n#endif",
    ),
    (
        '\tCHECK_BUG(pthread_setcancelstate(PTHREAD_CANCEL_DISABLE, &as->cs),\n\t\t"disabling cancellation");',
        '#ifndef __ANDROID__\n\tCHECK_BUG(pthread_setcancelstate(PTHREAD_CANCEL_DISABLE, &as->cs),\n\t\t"disabling cancellation");\n#endif',
    ),
    (
        '\tCHECK_BUG(pthread_setcancelstate(as->cs, NULL),\n\t\t"re-enabling cancellation");',
        '#ifndef __ANDROID__\n\tCHECK_BUG(pthread_setcancelstate(as->cs, NULL),\n\t\t"re-enabling cancellation");\n#endif',
    ),
)
for original, replacement in edits:
    if source.count(original) != 1:
        raise SystemExit(f"Pinned Git 2.52 source does not match Android cancellation adaptation: {original!r}")
    source = source.replace(original, replacement)
path.write_text(source)
print("Pinned Git 2.52 Android Bionic pthread adaptation verified")
PY
# Local Git operations run under the Android app UID. Keep required zlib,
# but do not add unverified bundled curl/OpenSSL dynamic libraries.
# Android Bionic exposes pthread APIs in libc, so pass an empty PTHREAD_LIBS
# to prevent Git's upstream Makefile from linking unavailable -lpthread.
# Git's Makefile uses SHELL_PATH for build-time generators AND target runtime.
# Build generators must execute on Linux, while compiled paths and scripts must
# retain Android's /system/bin/sh. Supply both target quoting variables explicitly.
make -C "$SOURCE" -j 2 git \
  "CC=$CC" "AR=$AR" "RANLIB=$RANLIB" \
  "SHELL_PATH=/bin/sh" "SHELL_PATH_SQ=/system/bin/sh" \
  'SHELL_PATH_CQ_SQ="/system/bin/sh"' \
  "DEFAULT_EDITOR=vi" "DEFAULT_PAGER=cat" "PTHREAD_LIBS=" \
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
