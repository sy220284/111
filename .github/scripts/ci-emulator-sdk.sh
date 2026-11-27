#!/usr/bin/env bash
# Hosted runner: no emulator bundle, AVD, or whole SDK is cached.
set -euo pipefail
SDK="$(realpath "${ANDROID_HOME:-${ANDROID_SDK_ROOT:?GitHub SDK missing}}")"
readarray -t matrix < <(python3 - <<'PY'
import json
p=json.load(open("android-app/tools/device-matrix.json"))
print(p["system_image_api"])
print(p["target"])
print(p["arch"])
print(p["command_line_tools"])
PY
)
test "${#matrix[@]}" -eq 4
IMAGE="android-${matrix[0]}/${matrix[1]}/${matrix[2]}"
PACKAGE="system-images;android-${matrix[0]};${matrix[1]};${matrix[2]}"
TOOLS="${matrix[3]}"
image_present() {
  python3 - "$SDK/system-images/$IMAGE" "$PACKAGE" <<'PY'
import pathlib, sys, xml.etree.ElementTree as ET
root, expected = pathlib.Path(sys.argv[1]), sys.argv[2]
if not (root/"system.img").is_file() or (root/"system.img").stat().st_size == 0:
    raise SystemExit(1)
if not (root/"package.xml").is_file() or not (root/"source.properties").is_file():
    raise SystemExit(1)
try:
    packages = [p for p in ET.parse(root/"package.xml").iter() if p.tag.rsplit("}",1)[-1]=="localPackage"]
    if len(packages)!=1 or packages[0].get("path")!=expected:
        raise SystemExit(1)
    revision = next((p for p in packages[0] if p.tag.rsplit("}",1)[-1]=="revision"), None)
    major = next((p.text for p in revision if p.tag.rsplit("}",1)[-1]=="major"), None) if revision is not None else None
    props = dict(line.split("=",1) for line in (root/"source.properties").read_text().splitlines() if "=" in line)
    if not major or not props.get("Pkg.Revision") or props["Pkg.Revision"].split(".")[0]!=major:
        raise SystemExit(1)
except (ET.ParseError, OSError, ValueError):
    raise SystemExit(1)
PY
}
case "${1:?locate/install/seal/verify-cache}" in
  locate)
    present=false
    if image_present; then present=true; fi
    printf 'path=%s\nkey=%s-%s-%s\npresent=%s\n' "$SDK/system-images/$IMAGE" "${matrix[0]}" "${matrix[1]}" "${matrix[2]}" "$present" >> "$GITHUB_OUTPUT"
    ;;
  install)
    MANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
    if [ ! -x "$MANAGER" ]; then
      MANAGER="$(find "$SDK/cmdline-tools" -type f -path '*/bin/sdkmanager' | sort -V | tail -n 1)"
    fi
    test -x "$MANAGER"
    packages=()
    if [ ! -x "$SDK/cmdline-tools/$TOOLS/bin/avdmanager" ]; then packages+=("cmdline-tools;$TOOLS"); fi
    if [ ! -x "$SDK/emulator/emulator" ]; then packages+=(emulator); fi
    if [ ! -x "$SDK/platform-tools/adb" ]; then packages+=(platform-tools); fi
    if ! image_present; then packages+=("$PACKAGE"); fi
    if [ "${#packages[@]}" -gt 0 ]; then
      "$MANAGER" --sdk_root="$SDK" --install "${packages[@]}"
    fi
    test -x "$SDK/emulator/emulator"
    test -x "$SDK/platform-tools/adb"
    test -x "$SDK/cmdline-tools/$TOOLS/bin/avdmanager"
    image_present
    printf 'ANDROID_HOME=%s\nANDROID_SDK_ROOT=%s\nCODEX_EMULATOR_SDK=%s\n' "$SDK" "$SDK" "$SDK" >> "$GITHUB_ENV"
    ;;
  seal)
    image_present
    cd "$SDK/system-images/$IMAGE"
    find . -type f ! -name .111-image-sha256 -print0 | sort -z | xargs -0 sha256sum > .111-image-sha256
    test -s .111-image-sha256
    ;;
  verify-cache)
    image_present
    cd "$SDK/system-images/$IMAGE"
    test -s .111-image-sha256
    sha256sum -c .111-image-sha256 --quiet
    ;;
  *) exit 2 ;;
esac
