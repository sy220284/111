#!/usr/bin/env bash
# Run the verified SDK image without any sdkmanager installation in consumers.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
: "${CODEX_EMULATOR_SDK:?missing verified emulator component}"
: "${EMULATOR_PROFILE:?missing device profile}"
export ANDROID_HOME="$CODEX_EMULATOR_SDK" ANDROID_SDK_ROOT="$CODEX_EMULATOR_SDK"
export ANDROID_AVD_HOME="${RUNNER_TEMP:-/tmp}/111-avd-$$"
export ANDROID_USER_HOME="${RUNNER_TEMP:-/tmp}/111-android-home-$$"
mkdir -p "$ANDROID_AVD_HOME" "$ANDROID_USER_HOME"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
export ANDROID_SERIAL=emulator-5554
MATRIX="$ROOT/android-app/tools/device-matrix.json"
PACKAGE="$(python3 - "$MATRIX" <<'PY'
import json,sys
p=json.load(open(sys.argv[1])); print("system-images;android-"+p["system_image_api"]+";"+p["target"]+";"+p["arch"])
PY
)"
MANAGER="$(find "$ANDROID_HOME/cmdline-tools" -type f -name avdmanager -print -quit)"
test -x "$MANAGER"
test -r /dev/kvm && test -w /dev/kvm
LOG="${RUNNER_TEMP:-/tmp}/111-emulator-$$.log"
PID=""
cleanup() {
  result=$?
  trap - EXIT
  if [ -n "$PID" ]; then
    timeout 15 adb -s "$ANDROID_SERIAL" emu kill >/dev/null 2>&1 || kill "$PID" 2>/dev/null || true
    wait "$PID" 2>/dev/null || true
  fi
  if [ "$result" -ne 0 ]; then tail -n 120 "$LOG" 2>/dev/null || true; fi
  rm -rf "$ANDROID_AVD_HOME" "$ANDROID_USER_HOME"
  exit "$result"
}
trap cleanup EXIT
printf 'no\n' | "$MANAGER" create avd --force -n test --package "$PACKAGE"
python3 "$ROOT/android-app/tools/device_matrix.py" --configure-avd "$EMULATOR_PROFILE"
if [ -n "${EMULATOR_PRE_SCRIPT:-}" ]; then bash -euo pipefail -c "$EMULATOR_PRE_SCRIPT"; fi
mapfile -t CONFIG < <(python3 - "$MATRIX" <<'PY'
import json,sys,shlex
p=json.load(open(sys.argv[1])); print("hw.cpu.ncore="+str(p["cores"])); print("hw.ramSize="+p["ram"]); print("disk.dataPartition.size="+p["disk"]); print(*shlex.split(p["emulator_options"]),sep="\n")
PY
)
python3 - "$ANDROID_AVD_HOME/test.avd/config.ini" "${CONFIG[@]:0:3}" <<'PY'
import pathlib,sys
p=pathlib.Path(sys.argv[1]); updates=dict(x.split("=",1) for x in sys.argv[2:]); lines=[x for x in p.read_text().splitlines() if x.split("=",1)[0].strip() not in updates]; p.write_text("\n".join(lines+[f"{k}={v}" for k,v in updates.items()])+"\n")
PY
"$ANDROID_HOME/emulator/emulator" -port 5554 -avd test -accel on "${CONFIG[@]:3}" > "$LOG" 2>&1 &
PID=$!
deadline=$((SECONDS+1200))
while [ "$SECONDS" -lt "$deadline" ]; do
  kill -0 "$PID"
  if [ "$(timeout 10 adb -s "$ANDROID_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)" = 1 ]; then break; fi
  sleep 2
done
test "$(timeout 10 adb -s "$ANDROID_SERIAL" shell getprop sys.boot_completed | tr -d '\r')" = 1
test "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" = 37
test "$(adb shell getconf PAGE_SIZE | tr -d '\r')" = 16384
adb shell input keyevent 82
for key in window_animation_scale transition_animation_scale animator_duration_scale; do adb shell settings put global "$key" 0.0; done
if [ "${1:-}" = "--probe" ]; then
  python3 "$ROOT/android-app/tools/device_matrix.py" --verify-resources "${RUNNER_TEMP:-/tmp}/111-emulator-probe.json"
else
  : "${EMULATOR_SCRIPT:?missing actual device test script}"
  bash -euo pipefail -c "$EMULATOR_SCRIPT"
fi
