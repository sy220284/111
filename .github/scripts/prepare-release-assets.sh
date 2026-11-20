#!/usr/bin/env bash
# Prepare the signed ARM64 phone APK and optional verified binary patch.
set -euo pipefail

: "${RELEASE_VERSION:?missing RELEASE_VERSION}"
: "${RELEASE_TAG:?missing RELEASE_TAG}"
: "${GITHUB_REPOSITORY:?missing GITHUB_REPOSITORY}"
: "${GH_TOKEN:?missing GH_TOKEN}"

OUT="$GITHUB_WORKSPACE/.release-assets"
APK_DIR="android-app/app/build/outputs/apk/release"
mkdir -p "$OUT"
rm -f "$OUT"/*
PATCHES_JSON="$RUNNER_TEMP/codex-patches.json"
APKS_JSON="$RUNNER_TEMP/codex-apks.json"
printf '[]\n' > "$PATCHES_JSON"
printf '[]\n' > "$APKS_JSON"

for ABI in arm64-v8a; do
  INPUT="$APK_DIR/app-$ABI-release.apk"
  test -s "$INPUT"
  ASSET="codex-android-$ABI-$RELEASE_VERSION.apk"
  cp "$INPUT" "$OUT/$ASSET"
  SIZE="$(stat -c '%s' "$OUT/$ASSET")"
  SHA="$(sha256sum "$OUT/$ASSET" | awk '{print $1}')"
  jq --arg abi "$ABI" --arg asset "$ASSET" --arg sha "$SHA" --argjson size "$SIZE" \
    '. += [{abi:$abi,name:$asset,size:$size,sha256:$sha}]' "$APKS_JSON" > "$APKS_JSON.tmp"
  mv "$APKS_JSON.tmp" "$APKS_JSON"
done

if [ -n "${PREVIOUS_TAG:-}" ] && [ "$PREVIOUS_TAG" != "$RELEASE_TAG" ]; then
  HDIFF_VERSION=5.1.3
  HDIFF_ARCHIVE="hdiffpatch_v${HDIFF_VERSION}_bin_linux64.zip"
  HDIFF_URL="https://github.com/sisong/HDiffPatch/releases/download/v${HDIFF_VERSION}/${HDIFF_ARCHIVE}"
  HDIFF_SHA256=628963bf2ee9108a97260fa5eef44acd9ec94369b76090a957c9182b3abbb558
  HDIFF_ZIP="$RUNNER_TEMP/$HDIFF_ARCHIVE"
  HDIFF_DIR="$RUNNER_TEMP/hdiffpatch"
  mkdir -p "$HDIFF_DIR"
  if curl --fail --location --retry 3 --retry-delay 2 "$HDIFF_URL" -o "$HDIFF_ZIP" \
      && printf '%s  %s\n' "$HDIFF_SHA256" "$HDIFF_ZIP" | sha256sum -c - \
      && unzip -q "$HDIFF_ZIP" -d "$HDIFF_DIR"; then
    HDIFFZ="$(find "$HDIFF_DIR" -type f -name hdiffz -print -quit)"
    HPATCHZ="$(find "$HDIFF_DIR" -type f -name hpatchz -print -quit)"
    if [ -n "$HDIFFZ" ] && [ -n "$HPATCHZ" ]; then
      chmod +x "$HDIFFZ" "$HPATCHZ"
      for ABI in arm64-v8a; do
        PREVIOUS_DIR="$RUNNER_TEMP/previous-$ABI"
        mkdir -p "$PREVIOUS_DIR"
        if ! gh release download "$PREVIOUS_TAG" --pattern "codex-android-$ABI-*.apk" --dir "$PREVIOUS_DIR"; then
          echo "::notice::未找到上一版本的 $ABI APK，跳过该架构增量包"
          continue
        fi
        mapfile -t OLD_FILES < <(find "$PREVIOUS_DIR" -maxdepth 1 -type f -name '*.apk')
        if [ "${#OLD_FILES[@]}" -ne 1 ]; then
          echo "::notice::$ABI 上版 APK 数量异常，跳过增量包"
          continue
        fi
        OLD="${OLD_FILES[0]}"
        NEW="$OUT/codex-android-$ABI-$RELEASE_VERSION.apk"
        PATCH_NAME="delta-${PREVIOUS_TAG#v}-to-$RELEASE_VERSION-$ABI.hpatch"
        PATCH="$OUT/$PATCH_NAME"
        RESTORED="$RUNNER_TEMP/replayed-$ABI.apk"
        if "$HDIFFZ" -s-64 -WD -w-2m -f -p-4 -c-zstd-19-24 -C-xxh128 \
            "$OLD" "$NEW" "$PATCH" \
            && "$HPATCHZ" -f "$OLD" "$PATCH" "$RESTORED" \
            && cmp -s "$NEW" "$RESTORED"; then
          PATCH_SIZE="$(stat -c '%s' "$PATCH")"
          NEW_SIZE="$(stat -c '%s' "$NEW")"
          if [ "$((PATCH_SIZE * 100))" -lt "$((NEW_SIZE * 65))" ]; then
            jq --arg abi "$ABI" --arg from "${PREVIOUS_TAG#v}" \
               --arg to "$RELEASE_VERSION" --arg asset "$PATCH_NAME" \
               --arg sha "$(sha256sum "$PATCH" | awk '{print $1}')" \
               --arg sourceSha "$(sha256sum "$OLD" | awk '{print $1}')" \
               --arg targetSha "$(sha256sum "$NEW" | awk '{print $1}')" \
               --argjson size "$PATCH_SIZE" \
              '. += [{abi:$abi,fromVersion:$from,toVersion:$to,algorithm:"hdiffpatch-window-zstd-v1",asset:$asset,size:$size,sha256:$sha,sourceSha256:$sourceSha,targetSha256:$targetSha}]' \
              "$PATCHES_JSON" > "$PATCHES_JSON.tmp"
            mv "$PATCHES_JSON.tmp" "$PATCHES_JSON"
          else
            rm -f "$PATCH"
          fi
        else
          echo "::warning::$ABI 增量包回放校验失败，仍发布完整 APK"
          rm -f "$PATCH"
        fi
      done
    fi
  else
    echo "::warning::增量工具不可用，仍发布 ARM64 手机完整 APK"
  fi
fi

jq -n --arg version "$RELEASE_VERSION" --slurpfile apks "$APKS_JSON" \
  --slurpfile patches "$PATCHES_JSON" \
  '{schema:2,targetVersion:$version,apks:$apks[0],patches:$patches[0]}' \
  > "$OUT/update-manifest.json"

(
  cd "$OUT"
  find . -maxdepth 1 -type f ! -name SHA256SUMS.txt -printf '%f\n' |
    sort | while IFS= read -r file; do sha256sum "$file"; done > SHA256SUMS.txt
  sha256sum -c SHA256SUMS.txt
)
jq -e '.schema == 2 and (.apks | length == 1) and (.apks[0].abi == "arm64-v8a")' \
  "$OUT/update-manifest.json"
ls -lh "$OUT"
