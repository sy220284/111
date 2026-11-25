#!/usr/bin/env bash
set -euo pipefail
: "${TAG:?}" "${SHA:?}" "${GH_TOKEN:?}"
test -s release-notes/release-notes.md
if gh release view "$TAG" --json isDraft,targetCommitish > "$RUNNER_TEMP/release-existing.json" 2>/dev/null; then
  jq -e --arg sha "$SHA" '.isDraft and .targetCommitish == $sha' "$RUNNER_TEMP/release-existing.json"
else
  gh release create "$TAG" --draft --target "$SHA" --title "$TAG" --notes-file release-notes/release-notes.md
fi
gh release upload "$TAG" .release-assets/* --clobber
mkdir -p "$RUNNER_TEMP/release-readback"
gh release download "$TAG" --dir "$RUNNER_TEMP/release-readback"
diff -qr .release-assets "$RUNNER_TEMP/release-readback"
(cd "$RUNNER_TEMP/release-readback" && sha256sum -c SHA256SUMS.txt)
git fetch origin main
python3 .github/scripts/select_release_candidate.py --repo "$GITHUB_REPOSITORY" --latest-main "$(git rev-parse FETCH_HEAD)" --trigger-sha "$SHA" --check-only
gh release edit "$TAG" --draft=false --latest
gh release view "$TAG" --json isDraft,targetCommitish,assets | jq -e --arg sha "$SHA" '.isDraft == false and .targetCommitish == $sha and (.assets | length >= 3)'
