#!/usr/bin/env bash
set -euo pipefail
DIR="${1:?candidate dir}"
OUT="${2:?evidence dir}"
mkdir -p "$OUT"
PKG=io.github.sy220284.codexandroid
: "${RELEASE_VERSION:?}" "${PREVIOUS_TAG:?}"
test -n "${ANDROID_SERIAL:-}"
HARNESS="${CODEX_DEVICE_TEST_HARNESS:?配置专用外部设备测试工具的绝对路径}"
test -x "$HARNESS"
test "$(adb -s "$ANDROID_SERIAL" get-state)" = device
test "$(adb shell getprop ro.product.cpu.abi | tr -d '\r')" = arm64-v8a
test "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != 1
adb shell getprop ro.build.version.sdk | tr -d '\r' > "$OUT/api.txt"
adb shell getprop ro.product.model | tr -d '\r' > "$OUT/model.txt"
(cd "$DIR" && sha256sum -c SHA256SUMS.txt)
APK="$DIR/codex-android-arm64-v8a-$RELEASE_VERSION.apk"
test -s "$APK"
# Release test devices must be dedicated, reset and registered by their operator.
if adb shell pm path "$PKG" | grep -q '^package:'; then
  echo "::error::专用测试设备尚未重置，拒绝覆盖已有安装"
  exit 1
fi
mkdir -p "$OUT/previous"
gh release download "$PREVIOUS_TAG" --pattern 'codex-android-arm64-v8a-*.apk' --dir "$OUT/previous"
OLD="$(find "$OUT/previous" -name '*.apk' -print -quit)"
test -s "$OLD"
adb install "$OLD" | tee "$OUT/previous-install.log"
grep -Fxq Success "$OUT/previous-install.log"
# Site-owned external UI suite seeds test data, exercises the real core, account,
# network tools, permission denial/revocation and migration. It may not use debug hooks.
"$HARNESS" seed --package "$PKG" --output "$OUT/seed.json"
adb install -r "$APK" | tee "$OUT/upgrade.log"
grep -Fxq Success "$OUT/upgrade.log"
adb shell am start -W -n "$PKG/.MainActivity" | tee "$OUT/launch.log"
grep -q 'Status: ok' "$OUT/launch.log"
INSTALLED="$(adb shell pm path "$PKG" | tr -d '\r' | sed -n 's/^package://p' | head -n1)"
test -n "$INSTALLED"
adb pull "$INSTALLED" "$OUT/installed.apk"
cmp "$APK" "$OUT/installed.apk"
"$HARNESS" verify --package "$PKG" --version "$RELEASE_VERSION" --apk "$APK" --seed "$OUT/seed.json" --output "$OUT/runtime.json"
python3 .github/scripts/verify_device_evidence.py "$OUT/runtime.json" "$RELEASE_VERSION" "$APK"
# Store path expected by the publication job; no APK copies or account data in evidence.
sha256sum "$APK" | sed 's#  candidate/#  .release-assets/#' > "$OUT/candidate.sha256"
rm -rf "$OUT/previous" "$OUT/installed.apk"
