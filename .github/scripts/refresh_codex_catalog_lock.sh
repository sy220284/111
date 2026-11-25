#!/usr/bin/env bash
# A branch that has the model catalog version lock must regenerate it from the
# same official history. Both the resolver and lock must exist together.
set -euo pipefail
resolver=android-app/tools/resolve_codex_catalog_version.py
lock=upstream/codex/codex-rs/models-manager/android-client-release.txt
if [[ -e "$resolver" || -e "$lock" ]]; then
  test -f "$resolver" && test -f "$lock"
  git fetch --tags --filter=blob:none codex-official
  python3 -B "$resolver" --upstream "$PWD" --marker CODEX_UPSTREAM_COMMIT --lock "$lock"
  git add -- "$lock"
fi
