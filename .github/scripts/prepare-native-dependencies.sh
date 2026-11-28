#!/usr/bin/env bash
# Single preparation path for the early workspace cache and both core consumers.
set -euo pipefail
cd "${GITHUB_WORKSPACE:?missing workspace}"
cat >> upstream/codex/codex-rs/core/Cargo.toml <<'TOML'
# Android target uses the same source-built OpenSSL strategy as Linux musl.
# This addition only applies to the CI checkout; upstream snapshot stays intact.
[target.aarch64-linux-android.dependencies]
openssl-sys = { workspace = true, features = ["vendored"] }

[target.x86_64-linux-android.dependencies]
openssl-sys = { workspace = true, features = ["vendored"] }
TOML
grep -A 2 'target.aarch64-linux-android.dependencies' upstream/codex/codex-rs/core/Cargo.toml
cd upstream/codex/codex-rs
python3 "$GITHUB_WORKSPACE/android-app/tools/prepare_android_code_mode.py" \
  "$GITHUB_WORKSPACE/upstream/codex/codex-rs/install-context/src/lib.rs" \
  --host-source "$GITHUB_WORKSPACE/upstream/codex/codex-rs/code-mode-host/src/main.rs" \
  --features-source "$GITHUB_WORKSPACE/upstream/codex/codex-rs/features/src/lib.rs"
python3 -B -m unittest discover -s "$GITHUB_WORKSPACE/android-app/tools" -p test_prepare_android_code_mode.py
python3 -B -m unittest discover -s "$GITHUB_WORKSPACE/android-app/tools" -p test_prepare_android_realtime_privacy.py
python3 "$GITHUB_WORKSPACE/android-app/tools/prepare_android_realtime_privacy.py" \
  "$GITHUB_WORKSPACE/upstream/codex/codex-rs/codex-api/src/endpoint/realtime_websocket/methods.rs"
python3 -B -m unittest discover -s "$GITHUB_WORKSPACE/android-app/tools" -p test_prepare_android_remote_delivery.py
python3 "$GITHUB_WORKSPACE/android-app/tools/prepare_android_remote_delivery.py" "$GITHUB_WORKSPACE/upstream/codex/codex-rs"
# Prepare the final locked graph, including host tools, before offline compilation.
cargo fetch --locked
# Use the official release workflow's workspace version update. External
# dependency versions remain locked; all runtime crates share this baseline.
python3 "$GITHUB_WORKSPACE/android-app/tools/resolve_codex_catalog_version.py" \
  --marker "$GITHUB_WORKSPACE/CODEX_UPSTREAM_COMMIT" \
  --lock "$PWD/models-manager/android-client-release.txt" \
  --stamp-workspace "$PWD/Cargo.toml"
cargo update --workspace --offline
cargo fetch --locked --offline
python3 "$GITHUB_WORKSPACE/android-app/tools/prepare_android_mxc.py" --cargo-home "${CARGO_HOME:-$HOME/.cargo}"
