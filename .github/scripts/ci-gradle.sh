#!/usr/bin/env bash
# Only recover the known Compose plugin lookup failure before tasks execute.
set -euo pipefail
LOG="$(mktemp)"
MODULE="$(mktemp)"
trap 'rm -f "$LOG" "$MODULE"' EXIT
run_gradle() {
  local rc=0
  ./android-app/gradlew "$@" 2>&1 | tee "$LOG" || rc=${PIPESTATUS[0]}
  return "$rc"
}
if run_gradle "$@"; then exit 0; else STATUS=$?; fi
VERSION="$(python3 - "$LOG" <<'PY'
import re, sys
from pathlib import Path
log = Path(sys.argv[1]).read_text(errors="replace")
match = re.search(r"Plugin \[id: 'org\.jetbrains\.kotlin\.plugin\.compose', version: '([0-9][0-9A-Za-z.\-]*)', artifact: 'org\.jetbrains\.kotlin:compose-compiler-gradle-plugin:\1', apply: false\] was not found", log)
# An unknown plugin is a configuration-stage failure; never rerun actual compilation.
if match:
    print(match[1])
PY
)"
if [ -z "$VERSION" ]; then exit "$STATUS"; fi
CENTRAL="https://repo.maven.apache.org/maven2"
MIRROR="https://maven-central.storage-download.googleapis.com/maven2"
MODULE_PATH="org/jetbrains/kotlin/compose-compiler-gradle-plugin/$VERSION/compose-compiler-gradle-plugin-$VERSION.module"
fetch_module() {
  curl --proto '=https' --proto-redir '=https' --fail --show-error --silent --location \
    --connect-timeout 15 --max-time 45 --write-out '%{http_code}' "$1/$MODULE_PATH" -o "$MODULE"
}
RETRY_ARGS=(--refresh-dependencies)
echo "::warning::Compose plugin resolution failed; checking the locked Maven Central module"
if ! PROBE_STATUS="$(fetch_module "$CENTRAL")"; then
  if [ "$PROBE_STATUS" != 429 ]; then exit "$STATUS"; fi
  echo "::warning::Maven Central returned HTTP 429; verifying the same module on Google's Maven Central mirror"
  fetch_module "$MIRROR" >/dev/null
  RETRY_ARGS=(--init-script "$PWD/android-app/gradle/ci-maven-central.init.gradle" "-Dcodex.ci.mavenCentralMirror=$MIRROR")
fi
python3 - "$MODULE" "$VERSION" <<'PY'
import hashlib, sys
from pathlib import Path
from xml.etree import ElementTree as ET
module, version = sys.argv[1:]
root = ET.parse("android-app/gradle/verification-metadata.xml").getroot()
digest = hashlib.sha256(Path(module).read_bytes()).hexdigest()
allowed = {x.get("value") for c in root.findall(".//{*}component")
    if c.get("group") == "org.jetbrains.kotlin" and c.get("name") == "compose-compiler-gradle-plugin" and c.get("version") == version
    for a in c.findall("{*}artifact") if a.get("name") == f"compose-compiler-gradle-plugin-{version}.module"
    for x in a.findall("{*}sha256")}
if digest not in allowed:
    raise SystemExit("Compose module does not match the reviewed SHA-256; refusing re-resolution")
print(f"Verified locked Compose module {version}: {digest}")
PY
# The retry retains the caller's dependency locks and verification mode.
./android-app/gradlew "$@" "${RETRY_ARGS[@]}" --info
