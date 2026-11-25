#!/usr/bin/env bash
# Fetch official Git objects without replacing the local source directory.
set -euo pipefail
requested="${1:-}"
if [[ -n "$requested" && ! "$requested" =~ ^[0-9a-f]{40}$ ]]; then
  echo "候选须为 40 位小写 SHA" >&2
  exit 1
fi
git remote add codex-official https://github.com/openai/codex.git
git config remote.codex-official.promisor true
git config remote.codex-official.partialclonefilter blob:none
git fetch --no-tags --filter=blob:none codex-official refs/heads/main:refs/remotes/codex-official/main
target="${requested:-$(git rev-parse refs/remotes/codex-official/main)}"
git merge-base --is-ancestor "$target" refs/remotes/codex-official/main
printf '%s\n' "$target"
