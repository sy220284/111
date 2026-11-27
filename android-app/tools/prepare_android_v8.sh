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
SOURCE_COMMIT=2768994f664e8a6e3aba27503606c58339136e2a
ARCHIVE="librusty_v8_ptrcomp_sandbox_release-$TARGET.a"
BINDING="src_binding_ptrcomp_sandbox_release-$TARGET.rs"
case "$TARGET" in
  aarch64-linux-android) CPU=arm64; ELF_ARCH=AArch64 ;;
  x86_64-linux-android) CPU=x64; ELF_ARCH=X86-64 ;;
esac
: "$ANDROID_NDK_HOME"
export RUSTUP_TOOLCHAIN=1.99.1
mkdir -p "$CACHE"
CACHE="$(realpath "$CACHE")"
EXPECTED_INPUTS="$(mktemp)"
TOOL_PROVENANCE="$(mktemp)"
trap 'rm -f "$EXPECTED_INPUTS" "$TOOL_PROVENANCE"' EXIT
# Resolve and record the actual official host tools before expensive source work.
python3 "$GITHUB_WORKSPACE/android-app/tools/v8_toolchain_provenance.py" > "$TOOL_PROVENANCE"
python3 "$GITHUB_WORKSPACE/.github/scripts/v8_artifacts.py" inputs --target "$TARGET" > "$EXPECTED_INPUTS"
test -z "$(find "$CACHE" -mindepth 1 -maxdepth 1 -print -quit)" || {
  echo "Raw V8 cache reuse is disabled; use a verified complete native artifact: $CACHE" >&2; exit 1;
}
    : "${ANDROID_NDK_HOME:?NDK must be installed}" "${LLVM_HOME:?missing official LLVM}"
    ANDROID_API=29
    NDK_LLVM="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"
    BIN="$NDK_LLVM/bin"
    SYSROOT="$NDK_LLVM/sysroot"
    LINKER="$BIN/${TARGET}${ANDROID_API}-clang"
    test -x "$LINKER"
    # libclang otherwise uses Cargo's unversioned Rust triple. NDK r30
    # rejects it when reading Bionic headers, even after Ninja succeeded.
    BINDGEN_TARGET_ARGS=("--target=${TARGET}${ANDROID_API}" "--sysroot=$SYSROOT")
    printf '#include <sys/cdefs.h>
static_assert(__ANDROID_MIN_SDK_VERSION__ == %s);
' "$ANDROID_API" |
        "$LLVM_HOME/bin/clang" "${BINDGEN_TARGET_ARGS[@]}" -x c++ -std=c++20 -fsyntax-only -
    echo "Verified Android bindgen headers for ${TARGET}${ANDROID_API}"
    ROOT="$RUNNER_TEMP/rusty-v8-android"
    SRC="$ROOT/rusty_v8-src"
    WRAPPER="$ROOT/wrapper"
    mkdir -p "$ROOT"
    git clone --depth 1 --branch v152.2.0 https://github.com/denoland/rusty_v8.git "$SRC"
    test "$(git -C "$SRC" rev-parse HEAD)" = "$SOURCE_COMMIT" || {
      echo "Pinned V8 source commit mismatch" >&2; exit 1;
    }
    git -C "$SRC" submodule update --init --recursive --depth 1
    : "${LLVM_HOME:?missing official LLVM}" "${LIBCLANG_PATH:?missing official libclang}"
    "$LLVM_HOME/bin/clang" --version | grep -E 'clang version 23\.'
    test -s "$LIBCLANG_PATH/libclang.so"
    command -v ninja
    command -v pkg-config
    mkdir -p "$SRC/third_party/android_toolchain" "$SRC/third_party/android_sdk"
    rm -rf "$SRC/third_party/android_ndk" "$SRC/third_party/android_toolchain/ndk" "$SRC/third_party/android_sdk/public"
    ln -s "$ANDROID_NDK_HOME" "$SRC/third_party/android_ndk"
    ln -s "$ANDROID_NDK_HOME" "$SRC/third_party/android_toolchain/ndk"
    test -s "$ANDROID_HOME/platforms/android-36/android.jar"
    test -x "$ANDROID_HOME/build-tools/36.0.0/aapt"
    ln -s "$ANDROID_HOME" "$SRC/third_party/android_sdk/public"
    CFG="$SRC/build/config/android/config.gni"
    if ! grep -Eq '^[[:space:]]*android_ndk_version[[:space:]]*=' "$CFG"; then
      printf '\ndeclare_args() {\n  android_ndk_version = "r30"\n}\n' >> "$CFG"
    fi
    mkdir -p "$SRC/build/rust"
    if [ ! -e "$SRC/build/rust/known-target-triples.txt" ]; then
      printf 'x86_64-linux-android\naarch64-linux-android\nx86_64-unknown-linux-gnu\n' > "$SRC/build/rust/known-target-triples.txt"
    fi
    # Both Android targets use the NDK sysroot.
    # The build host runs Linux tools from this runner, so it needs no separately
    # downloaded Debian sysroot. Patch the pinned V8 build recipe explicitly,
    # rather than letting its unconditional downloader block Android builds.
    python3 "$GITHUB_WORKSPACE/android-app/tools/adapt_android_v8_build.py" "$SRC/build.rs"
    mkdir -p "$WRAPPER/.cargo"
    printf '[target.%s]\nlinker = "%s"\n' "$TARGET" "$LINKER" > "$WRAPPER/.cargo/config.toml"
    export LIBCLANG_PATH="$LLVM_HOME/lib"
    export "BINDGEN_EXTRA_CLANG_ARGS_$(printf '%s' "$TARGET" | tr '-' '_')=${BINDGEN_TARGET_ARGS[*]} -isystem $SRC/third_party/libc++/src/include -isystem $SYSROOT/usr/include -isystem $SYSROOT/usr/include/$TARGET"
    export "CARGO_TARGET_$(printf '%s' "$TARGET" | tr '[:lower:]-' '[:upper:]_')_LINKER=$LINKER"
    # The pinned rusty_v8 revision provisions the GN version from its own
    # dependency manifest. A host GN override makes that input nondeterministic.
    unset GN
    test -s "$SRC/tools/ninja_gn_binaries.py"
    export NINJA="$(command -v ninja)"
    export V8_FROM_SOURCE=1
    : "${SCCACHE_PATH:?official sccache must be installed before building V8}"
    export SCCACHE="$SCCACHE_PATH"
    "$SCCACHE" --version | grep -Fx "sccache 0.20.1"
    "$SCCACHE" --start-server
    JOBS="$(python3 "$GITHUB_WORKSPACE/tools/build/build_progress.py" --jobs v8)"
    export GN_ARGS='v8_enable_sandbox=true v8_enable_pointer_compression=true android_static_analysis="off"'
    # The Cargo config in WRAPPER/.cargo applies only when Cargo runs from
    # WRAPPER (or a child). --manifest-path alone does not load that config.
    # Fail in seconds, rather than after compiling V8, if NDK linking fails.
    PREFLIGHT="$WRAPPER/linker-preflight"
    mkdir -p "$PREFLIGHT/src"
    cat > "$PREFLIGHT/Cargo.toml" <<'TOML'
[package]
name = "android-v8-linker-preflight"
version = "0.1.0"
edition = "2021"
TOML
    printf 'fn main() {}\n' > "$PREFLIGHT/src/main.rs"
    (
      cd "$PREFLIGHT"
      cargo build --release --target "$TARGET" -j "$JOBS"
    )
    ELF="$PREFLIGHT/target/$TARGET/release/android-v8-linker-preflight"
    test -s "$ELF"
    "$BIN/llvm-readelf" -h "$ELF" | grep -F "$ELF_ARCH"
    echo "Verified Android NDK linker before the lengthy V8 build"
    # Build the official crate with its checked-in dependency lock, not a
    # newly resolved wrapper dependency graph.
    (
      cd "$SRC"
      CARGO_TARGET_DIR="$ROOT/target" cargo build --locked --release -vv -p v8 --lib \
        --features v8_enable_sandbox --target "$TARGET" -j "$JOBS"
    )
    "$SCCACHE" --show-stats
    GN_OUT="$ROOT/target/$TARGET/release/gn_out"
    test -s "$GN_OUT/obj/librusty_v8.a"
    test -s "$GN_OUT/src_binding.rs"
    grep -Eq '^v8_enable_sandbox = true$' "$GN_OUT/args.gn"
    grep -Eq '^v8_enable_pointer_compression = true$' "$GN_OUT/args.gn"
    grep -Fx "v8_target_cpu = \"$CPU\"" "$GN_OUT/args.gn"
    grep -Fx 'target_os = "android"' "$GN_OUT/args.gn"
    cp "$GN_OUT/obj/librusty_v8.a" "$CACHE/$ARCHIVE"
    cp "$GN_OUT/src_binding.rs" "$CACHE/$BINDING"
    cp "$EXPECTED_INPUTS" "$CACHE/BUILD_INPUTS"
    {
      printf 'official_repository=https://github.com/denoland/rusty_v8\ncommit=%s\ntarget=%s\n' "$SOURCE_COMMIT" "$TARGET"
      printf 'upstream_lock_sha256='
      sha256sum "$SRC/Cargo.lock" | cut -d' ' -f1
      git -C "$SRC" submodule status --recursive
      cat "$TOOL_PROVENANCE"
      printf 'bindgen_target=%s\n' "${TARGET}${ANDROID_API}"
      printf 'sccache_version=%s\nsccache_sha256=%s\n' "$("$SCCACHE" --version)" "$(sha256sum "$SCCACHE" | cut -d' ' -f1)"
      cat "$GN_OUT/args.gn"
    } > "$CACHE/SOURCE_PROVENANCE"
    (cd "$CACHE" && sha256sum "$ARCHIVE" "$BINDING" BUILD_INPUTS SOURCE_PROVENANCE > SHA256SUMS)
test -s "$CACHE/$ARCHIVE" && test -s "$CACHE/$BINDING"
test -s "$CACHE/SOURCE_PROVENANCE"
cmp "$EXPECTED_INPUTS" "$CACHE/BUILD_INPUTS"
grep -Fx "commit=$SOURCE_COMMIT" "$CACHE/SOURCE_PROVENANCE"
grep -Fx "target=$TARGET" "$CACHE/SOURCE_PROVENANCE"
# Exact expected file set and digests, not an arbitrary cache-supplied list.
(cd "$CACHE" && sha256sum "$ARCHIVE" "$BINDING" BUILD_INPUTS SOURCE_PROVENANCE | cmp - SHA256SUMS)
echo "RUSTY_V8_ARCHIVE=$(realpath "$CACHE/$ARCHIVE")" >> "$GITHUB_ENV"
echo "RUSTY_V8_SRC_BINDING_PATH=$(realpath "$CACHE/$BINDING")" >> "$GITHUB_ENV"
echo "Sandbox-enabled Android V8 $VERSION source $SOURCE_COMMIT verified for $TARGET"
