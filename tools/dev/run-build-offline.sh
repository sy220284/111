#!/usr/bin/env bash
# Actual build tasks use only the verified component environment, without network.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ARGS=()
while IFS= read -r name; do
  case "$name" in
    JAVA_HOME|ANDROID_HOME|ANDROID_SDK_ROOT|ANDROID_NDK_HOME|GRADLE_USER_HOME|CODEX_RELEASE_KEYSTORE_PATH|CODEX_RELEASE_PASSWORD|CODEX_RELEASE_KEY_ALIAS|CARGO_HOME|RUSTC|RUSTDOC|RUSTFLAGS|RUSTUP_TOOLCHAIN|RUSTY_V8_*|CARGO_NET_OFFLINE|CARGO_BUILD_JOBS|CARGO_INCREMENTAL|CARGO_PROFILE_*|CARGO_TARGET_*|CC_*|CXX_*|AR_*|AR|RANLIB|CMAKE_ANDROID_NDK|CMAKE_ANDROID_API|OPENSSL_STATIC|LIBCLANG_PATH|LLVM_HOME|LD_LIBRARY_PATH|GITHUB_WORKSPACE|RUNNER_TEMP)
      ARGS+=("$name=${!name}")
      ;;
  esac
done < <(compgen -e)
exec bash "$ROOT/run-without-network.sh" "${ARGS[@]}" "$@"
