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
NEEDS_CPP_RUNTIME=0
ENTRIES=("codex-app-server:libcodex_server.so" "codex-code-mode-host:libcodex_code_mode_host.so")
for ENTRY in "${ENTRIES[@]}"; do
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
  # Check the actual dynamic dependencies; a valid ELF may still fail to launch
  # if Android's dynamic loader cannot find a required library.
  mapfile -t NEEDED < <(readelf -Wd "$SRC" | sed -n 's/.*Shared library: \[\([^]]*\)\].*/\1/p')
  for DEP in "${NEEDED[@]}"; do
    case "$DEP" in
      libc++_shared.so) NEEDS_CPP_RUNTIME=1 ;;
      libc.so|libm.so|libdl.so|liblog.so|libz.so|libandroid.so|libatomic.so|libaaudio.so|libOpenSLES.so|libmediandk.so) ;;
      *) echo "::error::$ABI $NAME has unbundled dependency: $DEP" >&2; exit 1 ;;
    esac
  done
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

if [ "$NEEDS_CPP_RUNTIME" -eq 1 ]; then
  NDK_HOME="${ANDROID_NDK_HOME:-${ANDROID_HOME:?}/ndk/27.2.12479018}"
  CPP="$NDK_HOME/sources/cxx-stl/llvm-libc++/libs/$ABI/libc++_shared.so"
  test -s "$CPP" || { echo "::error::$ABI missing required NDK C++ runtime $CPP" >&2; exit 1; }
  cp "$CPP" "$TARGET/libc++_shared.so"
  chmod 755 "$TARGET/libc++_shared.so"
  readelf -h "$TARGET/libc++_shared.so" | grep -Fq "$ARCH"
  mapfile -t CPP_ALIGNS < <(readelf -Wl "$TARGET/libc++_shared.so" | awk '$1=="LOAD" {print $NF}')
  test "${#CPP_ALIGNS[@]}" -gt 0
  for ALIGN in "${CPP_ALIGNS[@]}"; do
    if (( ALIGN < 16384 )); then
      echo "::error::$ABI NDK C++ shared runtime is not 16 KB aligned: $ALIGN" >&2
      exit 1
    fi
  done
  echo "$ABI bundled libc++_shared.so for Codex runtime"
fi


# Consume a verified 777 runtime component produced outside APK assembly.
: "${CODEX_RUNTIME_PREPARED:?missing verified 111 runtime component}"
case "$ABI" in
  arm64-v8a) CLANG="$(dirname "$STRIP")/aarch64-linux-android29-clang";;
  x86_64) CLANG="$(dirname "$STRIP")/x86_64-linux-android29-clang";;
esac
SOURCE_OUTPUT="${CODEX_GIT_SOURCE_OUTPUT:-${RUNNER_TEMP:-android-app/build}/git-corresponding-source.tar.xz}"
python3 android-app/tools/prepare_prebuilt_developer_runtime.py "$ABI" --prepared-dir "$CODEX_RUNTIME_PREPARED" \
  --native "$TARGET" --assets android-app/app/src/main/assets/development-runtime \
  --clang "$CLANG" --git-source-output "$SOURCE_OUTPUT"
