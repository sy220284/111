#!/usr/bin/env bash
# Keep one pinned repository and strict artifact verification. Only re-resolve
# the proven intermittent Compose plugin lookup failure, before tasks execute.
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
BASE="https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/compose-compiler-gradle-plugin/$VERSION"
echo "::warning::Compose plugin resolution failed; checking pinned Maven Central module before one re-resolution"
curl --proto '=https' --proto-redir '=https' --fail --show-error --silent --location --retry 2 --retry-delay 2 --connect-timeout 15 --max-time 60 "$BASE/compose-compiler-gradle-plugin-$VERSION.module" -o "$MODULE"
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
    raise SystemExit("Official Compose module does not match the reviewed SHA-256; refusing re-resolution")
print(f"Verified official Compose module {version}: {digest}")
PY
# No fallback repository, alternate version, relaxed checksum, or build-task retry.
./android-app/gradlew "$@" --refresh-dependencies --info
