#!/usr/bin/env bash
# CI product runtime only. Consume immutable original artifacts; never install the user development toolchain.
set -euo pipefail
if [ "$#" -ne 5 ]; then
  echo "Usage: $0 ABI ARTIFACT_ID SOURCE KEY NATIVE_DIR" >&2
  exit 2
fi
abi="$1"
artifact_id="$2"
source="$3"
key="$4"
native_dir="$5"
case "$abi" in
  arm64-v8a) component=runtime-arm64 ;;
  x86_64) component=runtime-x86 ;;
  *) echo "Unsupported Android ABI: $abi" >&2; exit 2 ;;
esac
strip="$ANDROID_HOME/ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
bundle="$RUNNER_TEMP/developer-runtime-bundle"
installed="$RUNNER_TEMP/developer-runtime-installed"
manifest_sha="$(python3 .github/scripts/runtime_artifacts.py download --abi "$abi" --artifact-id "$artifact_id" --source "$source" --key "$key" --output "$bundle")"
CODEX_RUNTIME_PREPARED="$(python3 tools/build/component-bundle.py install "$component" "$bundle" "$installed" --manifest-sha "$manifest_sha")"
export CODEX_RUNTIME_PREPARED
bash android-app/tools/package_native_runtime.sh "$abi" "$native_dir" "$strip"
