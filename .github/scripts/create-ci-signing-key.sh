#!/usr/bin/env bash
# Ephemeral test signing only; production credentials never enter this script.
set -euo pipefail
STORE="$RUNNER_TEMP/release-signing-smoke.jks"
keytool -genkeypair -noprompt -keystore "$STORE" -storetype PKCS12 \
  -alias 111-release -keyalg RSA -keysize 2048 -validity 1 \
  -storepass ci-only-password -keypass ci-only-password -dname "CN=111 CI signing smoke"
{
  echo "CODEX_RELEASE_KEYSTORE_PATH=$STORE"
  echo "CODEX_RELEASE_PASSWORD=ci-only-password"
  echo "CODEX_RELEASE_KEY_ALIAS=111-release"
} >> "$GITHUB_ENV"
