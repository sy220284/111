#!/usr/bin/env bash
# Boot an already available official SDK image; installation is owned by CI setup.
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
TOOLS_REVISION="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["command_line_tools"])' "$MATRIX")"
MANAGER="$ANDROID_HOME/cmdline-tools/$TOOLS_REVISION/bin/avdmanager"
test -x "$MANAGER"
test -r /dev/kvm && test -w /dev/kvm
LOG="${RUNNER_TEMP:-/tmp}/111-emulator-$$.log"
PID=""
stop_emulator() {
  if [ -z "$PID" ]; then return; fi
  if kill -0 "$PID" 2>/dev/null; then
    timeout 15 adb -s "$ANDROID_SERIAL" emu kill >/dev/null 2>&1 || kill "$PID" 2>/dev/null || true
    local stop_deadline=$((SECONDS+5))
    while kill -0 "$PID" 2>/dev/null && [ "$SECONDS" -lt "$stop_deadline" ]; do sleep 1; done
    if kill -0 "$PID" 2>/dev/null; then
      kill -KILL "$PID" 2>/dev/null || true
      sleep 1
    fi
  fi
  if kill -0 "$PID" 2>/dev/null; then
    echo "Emulator process did not exit after forced termination: $PID" >&2
    return 1
  fi
  wait "$PID" 2>/dev/null || true
  PID=""
}
cleanup() {
  result=$?
  trap - EXIT
  if [ "$result" -ne 0 ] && [ -n "$PID" ]; then
    echo "Android device failure: collecting system service and crash diagnostics" >&2
    timeout 10 adb -s "$ANDROID_SERIAL" shell getprop sys.boot_completed >&2 || true
    timeout 10 adb -s "$ANDROID_SERIAL" shell pidof system_server >&2 || true
    timeout 15 adb -s "$ANDROID_SERIAL" logcat -d -b crash -b system -b events -t 350 >&2 || true
  fi
  stop_emulator || result=1
  if [ "$result" -ne 0 ]; then tail -n 120 "$LOG" 2>/dev/null || true; fi
  rm -rf "$ANDROID_AVD_HOME" "$ANDROID_USER_HOME"
  exit "$result"
}
trap cleanup EXIT
printf 'no\n' | "$MANAGER" create avd --force -n test --package "$PACKAGE"
python3 "$ROOT/android-app/tools/device_matrix.py" --verify-avd-metadata
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
# A boot property alone can remain set while system_server restarts. Require
# real Binder calls and the same system_server PID over a stability window.
device_shell() { timeout 15 adb -s "$ANDROID_SERIAL" shell "$@"; }
ready_pid() {
  local before after compositor compositor_after pkg settings
  [ "$(device_shell getprop sys.boot_completed | tr -d '\r')" = 1 ] || return 1
  before="$(device_shell pidof system_server | tr -d '\r')" || return 1
  [[ "$before" =~ ^[0-9]+$ ]] || return 1
  compositor="$(device_shell pidof surfaceflinger | tr -d '\r')" || return 1
  [[ "$compositor" =~ ^[0-9]+$ ]] || return 1
  pkg="$(device_shell cmd package path android | tr -d '\r')" || return 1
  [[ "$pkg" == package:* ]] || return 1
  settings="$(device_shell settings get global window_animation_scale | tr -d '\r')" || return 1
  [[ "$settings" =~ ^(null|[0-9]+(\.[0-9]+)?)$ ]] || return 1
  device_shell cmd activity get-current-user >/dev/null || return 1
  after="$(device_shell pidof system_server | tr -d '\r')" || return 1
  [ "$before" = "$after" ] || return 1
  compositor_after="$(device_shell pidof surfaceflinger | tr -d '\r')" || return 1
  [ "$compositor" = "$compositor_after" ] || return 1
  printf '%s:%s\n' "$before" "$compositor"
}
start_emulator() {
  "$ANDROID_HOME/emulator/emulator" -port 5554 -avd test -accel on "${CONFIG[@]:3}" "$@" >> "$LOG" 2>&1 &
  PID=$!
}
await_system_ready() {
  local deadline="$1" stable_since=0 current
  last_pid=""
  while [ "$SECONDS" -lt "$deadline" ]; do
    if ! kill -0 "$PID" 2>/dev/null; then return 2; fi
    if current="$(ready_pid)"; then
      if [ "$current" != "$last_pid" ]; then
        last_pid="$current"
        stable_since=$SECONDS
      elif [ "$((SECONDS-stable_since))" -ge 30 ]; then
        return 0
      fi
    else
      last_pid=""
    fi
    sleep 2
  done
  return 1
}
deadline=$((SECONDS+1200))
last_pid=""
start_emulator
boot_status=0
await_system_ready "$deadline" || boot_status=$?
if [ "$boot_status" -eq 2 ] && [ "$SECONDS" -lt "$deadline" ]; then
  echo "Emulator exited before system services became ready; retrying once on a clean AVD" >&2
  tail -n 60 "$LOG" >&2
  stop_emulator
  start_emulator -wipe-data
  boot_status=0
  await_system_ready "$deadline" || boot_status=$?
fi
if [ "$boot_status" -ne 0 ]; then
  echo "Android system services failed to become stable before the boot deadline (status $boot_status)" >&2
  exit 1
fi
echo "Android system services ready; system_server:surfaceflinger=$last_pid"
test "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" = 37
test "$(adb shell getconf PAGE_SIZE | tr -d '\r')" = 16384
# A live system_server does not prove that HOME has a focusable window.
# Injecting KEYCODE_MENU here can start an input-dispatch timeout against an
# unfocused launcher before the app is even installed. Use WindowManager's
# non-input keyguard operation, then verify the real foreground window.
device_shell wm dismiss-keyguard
for key in window_animation_scale transition_animation_scale animator_duration_scale; do device_shell settings put global "$key" 0.0; done
home_component="$(device_shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tr -d '\r' | tail -n 1)"
[[ "$home_component" =~ ^[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$ ]] || { echo "Unable to resolve the actual HOME activity: $home_component" >&2; exit 1; }
home_package="${home_component%%/*}"
home_focus_ready() {
  local dump="$1" package="$2" focus
  focus="$(printf '%s\n' "$dump" | awk '/^[[:space:]]*mCurrentFocus=/ {sub(/^[[:space:]]*mCurrentFocus=/,""); value=$0} END {print value}')"
  [[ "$focus" == Window\{* && "$focus" == *" $package/"* && "$focus" != *"Application Not Responding"* && "$focus" != *"Application Error"* ]]
}
focus_deadline=$((SECONDS+120))
focus_stable_since=-1
focus_ready=false
while [ "$SECONDS" -lt "$focus_deadline" ]; do
  kill -0 "$PID"
  if dump="$(device_shell dumpsys window)" && home_focus_ready "$dump" "$home_package"; then
    if [ "$focus_stable_since" -lt 0 ]; then focus_stable_since=$SECONDS; fi
    if [ "$((SECONDS-focus_stable_since))" -ge 10 ]; then focus_ready=true; break; fi
  else
    focus_stable_since=-1
  fi
  sleep 2
done
if [ "$focus_ready" != true ]; then
  echo "HOME failed to acquire a stable focused window before test installation: $home_component" >&2
  device_shell dumpsys window >&2 || true
  exit 1
fi
echo "Android HOME focused and stable: $home_component"
if [ "${1:-}" = "--probe" ]; then
  python3 "$ROOT/android-app/tools/device_matrix.py" --verify-resources "${RUNNER_TEMP:-/tmp}/111-emulator-probe.json"
else
  : "${EMULATOR_SCRIPT:?missing actual device test script}"
  bash -euo pipefail -c "$EMULATOR_SCRIPT"
fi
