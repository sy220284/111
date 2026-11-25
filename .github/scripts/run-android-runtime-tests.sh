#!/usr/bin/env bash
# A clean, dedicated emulator or test phone is required; no credentials or user data.
set -euo pipefail
APP="${1:?APK}"
TEST="${2:?instrumentation APK}"
ABI="${3:?expected ABI}"
OUT="${4:?evidence directory}"
PKG=io.github.sy220284.codexandroid
mkdir -p "$OUT"
test -s "$APP"; test -s "$TEST"
adb wait-for-device
adb shell getprop ro.product.cpu.abi | tr -d '\r' | tee "$OUT/abi.txt"
test "$(cat "$OUT/abi.txt")" = "$ABI"
adb shell getprop ro.build.version.sdk | tr -d '\r' > "$OUT/api.txt"
sha256sum "$APP" "$TEST" > "$OUT/apks.sha256"
# Avoid replacing a user's installed app, whose data/credentials could be exposed to tests.
if adb shell pm path "$PKG" | grep -q '^package:'; then
  echo "需要专用干净测试设备：发现已安装的 111，拒绝覆盖" >&2
  exit 1
fi
adb install "$APP"
adb shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow
adb install "$TEST"
adb shell am start -W -n "$PKG/.MainActivity"
adb shell am instrument -w -r -e class "$PKG.CoreStartupInstrumentedTest,$PKG.RuntimeOwnershipInstrumentedTest" "$PKG.test/androidx.test.runner.AndroidJUnitRunner" | tee "$OUT/instrumentation.log"
grep -Eq 'OK \([1-9][0-9]* tests?\)' "$OUT/instrumentation.log"
! grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED' "$OUT/instrumentation.log"
for NAME in bundledCoreInitializesAndAnswersAccountRead realCoreCrashRecoversWithoutReplayingAndRejectsRetiredSession androidPtySupportsInteractiveInputAndResize terminalLongRunningProcessCanBeStoppedByProcessId foregroundServiceReportsReadyOnlyAfterAndroidPromotion; do
  grep -Fq "test=$NAME" "$OUT/instrumentation.log"
done
echo "真实 Android 内核、故障恢复、终端和前台服务回执通过" | tee "$OUT/result.txt"
