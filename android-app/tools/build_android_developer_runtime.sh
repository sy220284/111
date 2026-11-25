#!/usr/bin/env bash
# Build the additional Android-native developer payload from pinned upstream sources.
# Only the signed APK carries executables; Python/npm libraries are extracted as data.
set -euo pipefail
test "$#" -eq 3
TARGET="$1"; NDK="$2"; RELEASE="$(realpath "$3")"
case "$TARGET" in
  aarch64-linux-android) CPU=arm64; PY_SHA=423c80b957330cf0ebb92b9b20f2294c292144a441bdb0ceb8c69d14928e569b ;;
  x86_64-linux-android) CPU=x64; PY_SHA=6ebda1fc3f28efc5861cad397254e8ce5d81ba4f83a3cb6a0c124c3826a0e14c ;;
  *) echo "Unsupported target: $TARGET" >&2; exit 2 ;;
esac
BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
CC="$BIN/$TARGET"29-clang
CXX="$BIN/$TARGET"29-clang++
ALIGN="-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"
WORK="$(mktemp -d "$RUNNER_TEMP/developer-$CPU.XXXXXX")"
OUT="$RELEASE/development-runtime"
mkdir -p "$OUT/native" "$WORK/data/lib" "$WORK/data/licenses"
fetch() {
  local url="$1" sum="$2" dest="$3"
  curl --fail --location --retry 3 --proto '=https' --proto-redir '=https' "$url" -o "$dest"
  printf '%s  %s\n' "$sum" "$dest" | sha256sum -c -
}
# Reuse the exact static OpenSSL installation already compiled for the Codex core.
mapfile -t SSL_HEADERS < <(find "$RELEASE/../build" -path '*/openssl-build/install/include/openssl/ssl.h' -type f)
test "${#SSL_HEADERS[@]}" -eq 1
SSL="${SSL_HEADERS[0]%/include/openssl/ssl.h}"
test -s "$SSL/lib/libssl.a"; test -s "$SSL/lib/libcrypto.a"
fetch https://github.com/curl/curl/releases/download/curl-8_23_0/curl-8.23.0.tar.xz \
  8dadd7d2536a314f982ad05a651b713f426bf7d02cc2c76cffb9796f2218e6c5 "$WORK/curl.tar.xz"
tar -xJf "$WORK/curl.tar.xz" -C "$WORK"
CURL_PREFIX="$WORK/curl-install"
(
  cd "$WORK/curl-8.23.0"
  CC="$CC" AR="$BIN/llvm-ar" RANLIB="$BIN/llvm-ranlib" \
  CFLAGS="-O2 -fPIC" LDFLAGS="$ALIGN" PKG_CONFIG_LIBDIR="$SSL/lib/pkgconfig" \
    ./configure --host="$TARGET" --prefix="$CURL_PREFIX" \
    --disable-shared --enable-static --with-openssl="$SSL" \
    --without-libpsl --without-zstd --without-brotli --without-nghttp2 \
    --without-libidn2 --without-librtmp --without-libssh2 --without-libssh \
    --disable-ldap --disable-ldaps --disable-manual --without-ca-bundle --without-ca-path
  make -j2
  make install
)
cp "$CURL_PREFIX/bin/curl" "$RELEASE/curl"
cp "$WORK/curl-8.23.0/COPYING" "$WORK/data/licenses/curl.txt"
# Git and the CLI use the same verified curl build and TLS dependency.
bash "$GITHUB_WORKSPACE/android-app/tools/build_android_git.sh" "$TARGET" "$BIN" \
  "$TARGET"29-clang "$RELEASE/git" "$CURL_PREFIX" "$SSL"

# Official CPython Android distribution, with its standard library and extension modules.
fetch "https://www.python.org/ftp/python/3.14.10/python-3.14.10-$TARGET.tar.gz" "$PY_SHA" "$WORK/python.tar.gz"
mkdir "$WORK/python"; tar -xzf "$WORK/python.tar.gz" -C "$WORK/python"
mapfile -t PYLIBS < <(find "$WORK/python" -type f -name libpython3.14.so)
test "${#PYLIBS[@]}" -eq 1
PYLIB="$(dirname "${PYLIBS[0]}")"; PYPREFIX="$(dirname "$PYLIB")"
test -f "$PYPREFIX/include/python3.14/Python.h"
test -d "$PYLIB/python3.14"
cp -L "$PYLIB"/libpython3.14.so "$OUT/native/"
while IFS= read -r -d '' lib; do cp -L "$lib" "$OUT/native/"; done \
  < <(find "$PYLIB" -maxdepth 1 -type f -name 'lib*_python.so' -print0)
cp -a "$PYLIB/python3.14" "$WORK/data/lib/"
# Keep the full standard library; do not remove modules to meet a guessed size.
printf '%s\n' 'pip==26.3 --hash=sha256:6c36ae7da8695be67e3cd9f7d87919667ced53f57ce2ede75d89b5912ed2bc4c' > "$WORK/pip-requirements.txt"
python3 -m pip download --require-hashes --no-deps --only-binary=:all: -r "$WORK/pip-requirements.txt" -d "$WORK"
python3 -m zipfile -e "$WORK/pip-26.3-py3-none-any.whl" "$WORK/data/lib/python3.14/site-packages"
"$CC" -O2 $ALIGN -I"$PYPREFIX/include/python3.14" \
  "$GITHUB_WORKSPACE/android-app/tools/developer_python.c" -L"$OUT/native" -lpython3.14 \
  -o "$RELEASE/python"
# Preserve release notices; CPython's stdlib contains its LICENSE.txt.
test -f "$WORK/data/lib/python3.14/LICENSE.txt" || {
  mapfile -t LICENSES < <(find "$WORK/python" -name LICENSE -type f)
  test "${#LICENSES[@]}" -gt 0
  cp "${LICENSES[0]}" "$WORK/data/licenses/python.txt"
}

# Node is an independent runtime: V8 Code Mode does not implement Node APIs.
fetch https://nodejs.org/dist/v24.22.3/node-v24.22.3.tar.xz \
  48ca3aa8a9b02133356922ef4fc80200e00598c80a4ef446f7c39a459222593d "$WORK/node.tar.xz"
tar -xJf "$WORK/node.tar.xz" -C "$WORK"
(
  cd "$WORK/node-v24.22.3"
  # The upstream Android helper applies this Android V8 patch. Fail on drift.
  patch --batch --forward deps/v8/src/trap-handler/trap-handler.h < android-patches/trap-handler.h.patch
  export PATH="$BIN:$PATH" CC="$CC" CXX="$CXX" AR="$BIN/llvm-ar" RANLIB="$BIN/llvm-ranlib"
  export CC_host=gcc CXX_host=g++ AR_host=ar
  export GYP_DEFINES="target_arch=$CPU v8_target_arch=$CPU android_target_arch=$CPU host_os=linux OS=android android_ndk_path=$NDK"
  export LDFLAGS="$ALIGN"
  ./configure --dest-cpu="$CPU" --dest-os=android --openssl-no-asm --cross-compiling
  make -j2
  test -x out/Release/node
  cp out/Release/node "$RELEASE/node"
  cp -a deps/npm "$WORK/data/npm"
  cp LICENSE "$WORK/data/licenses/node.txt"
)
test -s "$WORK/data/npm/bin/npm-cli.js"
"$CC" -O2 $ALIGN "$GITHUB_WORKSPACE/android-app/tools/developer_npm.c" -o "$RELEASE/npm"
# Asset data can contain Python extension libraries, but cannot contain symlinks.
python3 - "$WORK/data" "$OUT/development-runtime.zip" <<'PY'
import pathlib, sys, zipfile
root, out = map(pathlib.Path, sys.argv[1:])
with zipfile.ZipFile(out, "w", zipfile.ZIP_STORED) as archive:
    for path in sorted(root.rglob("*")):
        if path.is_symlink():
            raise RuntimeError(f"Unexpected symlink in runtime data: {path}")
        if path.is_file():
            archive.write(path, path.relative_to(root).as_posix())
PY
sha256sum "$OUT/development-runtime.zip" | awk '{print $1}' > "$OUT/development-runtime.sha256"
printf 'Python 3.14.10 / pip 26.3 / Node.js 24.22.3 / curl 8.23.0\n' > "$OUT/development-runtime.version"
