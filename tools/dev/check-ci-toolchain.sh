#!/usr/bin/env bash
# One implementation for automatic toolchain preflight and manual developer warm-up.
set -euo pipefail
python3 -m unittest discover -s tools/dev -p 'test_*.py'
python3 -m unittest discover -s tools/build -p 'test_*.py'
python3 tools/dev/audit_maven_metadata.py
python3 tools/dev/ai-toolchain.py --root "$RUNNER_TEMP/shared" shared-install --artifacts-dir "$RUNNER_TEMP/downloads" --download > "$RUNNER_TEMP/shared.json"
LINT="$(jq -er '.actionlint' "$RUNNER_TEMP/shared.json")"
"$LINT/actionlint" .github/workflows/dev-toolchain.yml .github/workflows/prepare-developer-runtime.yml .github/workflows/ci.yml .github/workflows/release.yml .github/workflows/reconcile-ci-queue.yml
# Pinned actionlint predates GitHub's queue: max support. Ignore only this known syntax.
"$LINT/actionlint" -ignore 'unexpected key "queue" for "concurrency" section' .github/workflows/build-native-codex.yml .github/workflows/prepare-host-toolchain.yml .github/workflows/prepare-aux-toolchain.yml
