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
run_suite() {
  local CLASS="$1" LOG="$2"
  adb shell am instrument -w -r -e class "$PKG.$CLASS" "$PKG.test/androidx.test.runner.AndroidJUnitRunner" | tee "$LOG"
  grep -Eq 'OK \([1-9][0-9]* tests?\)' "$LOG"
  if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED' "$LOG"; then
    echo "真实运行测试失败：$CLASS" >&2
    exit 1
  fi
  grep -Fq 'INSTRUMENTATION_CODE: -1' "$LOG"
}
# No Activity-owned core may share the test core's isolated CODEX_HOME.
run_suite CoreStartupInstrumentedTest "$OUT/core.log"
adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/.MainActivity"
run_suite RuntimeOwnershipInstrumentedTest "$OUT/ownership.log"
cat "$OUT/core.log" "$OUT/ownership.log" > "$OUT/instrumentation.log"
for NAME in bundledDeveloperToolsActuallyExecute bundledDeveloperPackagesAndHttpsUseRealCore bundledCoreReportsAndroidCodeModeDefault bundledCoreInitializesAndAnswersAccountRead nativeCoreDiscoversAndCallsAndroidPreviewMcp realCoreCrashRecoversWithoutReplayingAndRejectsRetiredSession reservedRestartWaitsForConfirmedProcessExitBeforeCredentialChange androidPtySupportsInteractiveInputAndResize terminalLongRunningProcessCanBeStoppedByProcessId foregroundServiceReportsReadyOnlyAfterAndroidPromotion legacyBackupRestoresHistoryWithoutChangingRuntimeConfiguration realAnswerSnapshotSurvivesPortableRestore; do
  grep -Fq "test=$NAME" "$OUT/instrumentation.log"
done
echo "真实 Android 内核、故障恢复、终端和前台服务回执通过" | tee "$OUT/result.txt"
