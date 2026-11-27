#!/usr/bin/env bash
# A clean, dedicated emulator or test phone is required; no credentials or user data.
set -euo pipefail
if [ "$#" -ne 4 ] && [ "$#" -ne 6 ]; then
  echo "Expected baseline APKs and an optional complete target-37 APK pair" >&2
  exit 2
fi
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
EXPECTED_API="$(python3 -c 'import json; print(json.load(open("android-app/tools/device-matrix.json"))["api"])')"
EXPECTED_PAGE="$(python3 -c 'import json; print(json.load(open("android-app/tools/device-matrix.json"))["page_size"])')"
test "$(cat "$OUT/api.txt")" = "$EXPECTED_API"
adb shell getconf PAGESIZE | tr -d '\r' > "$OUT/page-size.txt"
test "$(cat "$OUT/page-size.txt")" = "$EXPECTED_PAGE"
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
  shift 2
  adb shell am instrument -w -r -e class "$PKG.$CLASS" "$@" "$PKG.test/androidx.test.runner.AndroidJUnitRunner" | tee "$LOG"
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
run_suite RealtimeAudioFocusInstrumentedTest "$OUT/audio-focus.log"
adb logcat -d -s CodexIsolationProbe:I '*:S' > "$OUT/isolation-capabilities.log"
grep -Fq 'privateRead=false privateWrite=false networkConnected=false' "$OUT/isolation-capabilities.log"
cat "$OUT/core.log" "$OUT/ownership.log" "$OUT/audio-focus.log" > "$OUT/instrumentation.log"
for NAME in bundledDeveloperToolsActuallyExecute bundledDeveloperPackagesAndHttpsUseRealCore realNetworkFailureAndTimeoutRecoverOnSameCore bundledCoreReportsAndroidCodeModeDefault bundledCoreInitializesAndAnswersAccountRead nativeCoreDiscoversAndCallsAndroidPreviewMcp realCoreCrashRecoversWithoutReplayingAndRejectsRetiredSession reservedRestartWaitsForConfirmedProcessExitBeforeCredentialChange androidPtySupportsInteractiveInputAndResize terminalLongRunningProcessCanBeStoppedByProcessId foregroundServiceReportsReadyOnlyAfterAndroidPromotion legacyBackupRestoresHistoryWithoutChangingRuntimeConfiguration realAnswerSnapshotSurvivesPortableRestore isolatedProcessHasSeparateUidAndDeniesUnbrokeredAccess portableArchiveRoundTripPreservesProjectsDraftsAttachmentsAndHistory competingAudioFocusInterruptsAndAllowsNewSession; do
  grep -Fq "test=$NAME" "$OUT/instrumentation.log"
done
echo "真实 Android 内核、故障恢复、终端和前台服务回执通过" | tee "$OUT/result.txt"

# Run the target-37 compatibility candidate on the same dedicated emulator, separately
# from the complete target-35 runtime verdict. No user device or data is touched.
if [ "$#" -eq 6 ]; then
  CANDIDATE="$5"
  CANDIDATE_TEST="$6"
  test -s "$CANDIDATE"; test -s "$CANDIDATE_TEST"
  mkdir -p "$OUT/target37"
  sha256sum "$CANDIDATE" "$CANDIDATE_TEST" > "$OUT/target37/apks.sha256"
  adb shell am force-stop "$PKG"
  adb uninstall "$PKG.test"
  adb uninstall "$PKG"
  adb install "$CANDIDATE"
  adb install "$CANDIDATE_TEST"
  adb shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow
  adb shell dumpsys package "$PKG" > "$OUT/target37/package.txt"
  grep -Eq 'targetSdk=37([[:space:]]|$)' "$OUT/target37/package.txt"
  PERMISSION=android.permission.ACCESS_LOCAL_NETWORK
  permission_state() {
    adb shell dumpsys package "$PKG" | grep -F "$PERMISSION: granted=$1"
  }
  # OS permission lifecycle, with real loopback network recovery and a real V8
  # startup while LAN is denied. Physical LAN routing still needs device testing.
  permission_state false | tee "$OUT/target37/initial-denied.txt"
  run_suite Android17CandidateInstrumentedTest "$OUT/target37/denied-ui.log" -e expectedLanGrant false
  run_suite 'CoreStartupInstrumentedTest#realNetworkFailureAndTimeoutRecoverOnSameCore' "$OUT/target37/denied-loopback.log"
  run_suite 'CoreStartupInstrumentedTest#bundledCodeModeHostCanInitializeV8OnAndroid' "$OUT/target37/denied-v8.log"
  adb shell am force-stop "$PKG"
  adb shell pm grant "$PKG" "$PERMISSION"
  permission_state true | tee "$OUT/target37/granted.txt"
  run_suite Android17CandidateInstrumentedTest "$OUT/target37/granted-ui.log" -e expectedLanGrant true
  run_suite 'CoreStartupInstrumentedTest#realNetworkFailureAndTimeoutRecoverOnSameCore' "$OUT/target37/granted-loopback.log"
  adb shell am force-stop "$PKG"
  adb shell pm revoke "$PKG" "$PERMISSION"
  permission_state false | tee "$OUT/target37/revoked.txt"
  run_suite Android17CandidateInstrumentedTest "$OUT/target37/revoked-ui.log" -e expectedLanGrant false
  run_suite 'CoreStartupInstrumentedTest#realNetworkFailureAndTimeoutRecoverOnSameCore' "$OUT/target37/revoked-loopback.log"
  run_suite RealtimeAudioFocusInstrumentedTest "$OUT/target37/audio-focus.log"
  run_suite 'CodexInteractionComponentsInstrumentedTest#expandedWindowLimitsContentWidthWithoutNarrowingPhoneLayout' "$OUT/target37/wide-content.log"
  echo "targetSdk 37 系统授权/撤销界面、真实核心回环、系统音频焦点及宽屏组件验收通过" | tee "$OUT/target37/result.txt"
fi
