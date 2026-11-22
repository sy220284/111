#!/usr/bin/env bash
# Android V8 for Code Mode. Match the Cargo.lock version and sandbox settings.
set -euo pipefail
if [ "$#" -ne 2 ]; then echo "Usage: $0 <target-triple> <cache-dir>" >&2; exit 2; fi
TARGET="$1"; CACHE="$2"
case "$TARGET" in aarch64-linux-android|x86_64-linux-android) ;; *) echo "Unsupported target $TARGET" >&2; exit 2;; esac
VERSION=152.2.0
python3 - "$GITHUB_WORKSPACE/upstream/codex/codex-rs/Cargo.lock" "$VERSION" <<'PY'
import sys, tomllib
with open(sys.argv[1], "rb") as f: lock = tomllib.load(f)
versions = {p["version"] for p in lock["package"] if p["name"] == "v8"}
if versions != {sys.argv[2]}:
    raise SystemExit(f"Code Mode V8 mismatch: Cargo.lock={versions}, Android builder={sys.argv[2]}")
PY
if [ "$TARGET" = aarch64-linux-android ]; then
  ARCHIVE="librusty_v8_ptrcomp_sandbox_release_aarch64-linux-android.a.gz"
  BINDING="src_binding_ptrcomp_sandbox_release_aarch64-linux-android.rs"
else
  ARCHIVE="librusty_v8_ptrcomp_sandbox_release_x86_64-linux-android.a"
  BINDING="src_binding_ptrcomp_sandbox_release_x86_64-linux-android.rs"
fi
mkdir -p "$CACHE"
if [ -s "$CACHE/SHA256SUMS" ]; then
  (cd "$CACHE" && sha256sum -c SHA256SUMS)
else
  test -z "$(find "$CACHE" -maxdepth 1 -type f -print -quit)" || {
    echo "Incomplete V8 cache, refusing partial reuse: $CACHE" >&2; exit 1;
  }
  if [ "$TARGET" = aarch64-linux-android ]; then
    BASE="https://github.com/DioNanos/codex-vl/releases/download/rusty-v8-v152.2.0-vl1"
    curl -fL --retry 3 "$BASE/$ARCHIVE" -o "$CACHE/$ARCHIVE"
    curl -fL --retry 3 "$BASE/$BINDING" -o "$CACHE/$BINDING"
    cat > "$CACHE/SHA256SUMS" <<'SHA'
422ddb869bda03c250081160231bc8940adab7d63b050ce1c468760dde92085c  librusty_v8_ptrcomp_sandbox_release_aarch64-linux-android.a.gz
13c9b755752972a7ee9748dbc2aecb1dc9e349baff6c7c019c395ff801779059  src_binding_ptrcomp_sandbox_release_aarch64-linux-android.rs
SHA
  else
    : " ${ANDROID_NDK_HOME:?NDK must be installed} "
    ROOT="$RUNNER_TEMP/rusty-v8-android"
    SRC="$ROOT/rusty_v8-src"
    WRAPPER="$ROOT/wrapper"
    mkdir -p "$ROOT"
    git clone --depth 1 --branch v152.2.0 https://github.com/denoland/rusty_v8.git "$SRC"
    test "$(git -C "$SRC" rev-parse HEAD)" = "2768994f664e8a6e3aba27503606c58339136e2a" || {
      echo "Pinned V8 source commit mismatch" >&2; exit 1;
    }
    git -C "$SRC" submodule update --init --recursive --depth 1
    sudo apt-get update
    sudo apt-get install -y ninja-build libssl-dev pkg-config
    if ! sudo apt-get install -y libclang-21-dev clang-21; then
      curl -fsSL https://apt.llvm.org/llvm.sh -o "$RUNNER_TEMP/llvm-21.sh"
      sudo bash "$RUNNER_TEMP/llvm-21.sh" 21
      sudo apt-get install -y libclang-21-dev clang-21
    fi
    test -f /usr/lib/llvm-21/lib/libclang.so || test -f /usr/lib/llvm-21/lib/libclang.so.1
    mkdir -p "$SRC/third_party/android_toolchain" "$SRC/third_party/android_sdk"
    rm -rf "$SRC/third_party/android_ndk" "$SRC/third_party/android_toolchain/ndk" "$SRC/third_party/android_sdk/public"
    ln -s "$ANDROID_NDK_HOME" "$SRC/third_party/android_ndk"
    ln -s "$ANDROID_NDK_HOME" "$SRC/third_party/android_toolchain/ndk"
    SDKMANAGER="$(find "$ANDROID_HOME/cmdline-tools" -type f -name sdkmanager -print -quit)"
    "$SDKMANAGER" "platforms;android-36" "build-tools;36.0.0"
    ln -s "$ANDROID_HOME" "$SRC/third_party/android_sdk/public"
    CFG="$SRC/build/config/android/config.gni"
    if ! grep -Eq '^[[:space:]]*android_ndk_version[[:space:]]*=' "$CFG"; then
      printf '\ndeclare_args() {\n  android_ndk_version = "r27"\n}\n' >> "$CFG"
    fi
    mkdir -p "$SRC/build/rust"
    if [ ! -e "$SRC/build/rust/known-target-triples.txt" ]; then
      printf 'x86_64-linux-android\naarch64-linux-android\nx86_64-unknown-linux-gnu\n' > "$SRC/build/rust/known-target-triples.txt"
    fi
    (cd "$SRC" && python3 build/linux/sysroot_scripts/install-sysroot.py --arch=amd64)
    mkdir -p "$WRAPPER/src" "$WRAPPER/.cargo"
    printf 'fn main() {}\n' > "$WRAPPER/src/main.rs"
    cat > "$WRAPPER/Cargo.toml" <<'TOML'
[package]
name = "android-v8-builder"
version = "0.1.0"
edition = "2021"
[dependencies]
v8 = { path = "../rusty_v8-src", features = ["v8_enable_sandbox"] }
TOML
    BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
    printf '[target.x86_64-linux-android]\nlinker = "%s/x86_64-linux-android29-clang"\n' "$BIN" > "$WRAPPER/.cargo/config.toml"
    SYSROOT="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
    export LIBCLANG_PATH=/usr/lib/llvm-21/lib
    export BINDGEN_EXTRA_CLANG_ARGS_x86_64_linux_android="--sysroot=$SYSROOT -isystem $SRC/third_party/libc++/src/include -isystem $SYSROOT/usr/include -isystem $SYSROOT/usr/include/x86_64-linux-android"
    test -x "$SRC/buildtools/linux64/gn"
    export GN="$SRC/buildtools/linux64/gn"
    export NINJA="$(command -v ninja)"
    export V8_FROM_SOURCE=1
    export GN_ARGS='v8_enable_sandbox=true v8_enable_pointer_compression=true android_static_analysis="off"'
    cargo build --release --target "$TARGET" --manifest-path "$WRAPPER/Cargo.toml" -j 2
    GN_OUT="$WRAPPER/target/$TARGET/release/gn_out"
    test -s "$GN_OUT/obj/librusty_v8.a"
    test -s "$GN_OUT/src_binding.rs"
    grep -Eq '^v8_enable_sandbox = true$' "$GN_OUT/args.gn"
    grep -Eq '^v8_enable_pointer_compression = true$' "$GN_OUT/args.gn"
    cp "$GN_OUT/obj/librusty_v8.a" "$CACHE/$ARCHIVE"
    cp "$GN_OUT/src_binding.rs" "$CACHE/$BINDING"
    (cd "$CACHE" && sha256sum "$ARCHIVE" "$BINDING" > SHA256SUMS)
  fi
  (cd "$CACHE" && sha256sum -c SHA256SUMS)
fi
test -s "$CACHE/$ARCHIVE" && test -s "$CACHE/$BINDING"
echo "RUSTY_V8_ARCHIVE=$(realpath "$CACHE/$ARCHIVE")" >> "$GITHUB_ENV"
echo "RUSTY_V8_SRC_BINDING_PATH=$(realpath "$CACHE/$BINDING")" >> "$GITHUB_ENV"
echo "Sandbox-enabled Android V8 $VERSION verified for $TARGET"
