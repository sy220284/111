#!/usr/bin/env bash
set -euo pipefail
python3 tools/build/native_toolchain.py install
LLVM="/usr/lib/llvm-$(python3 -c 'import json; print(json.load(open("tools/build/native-toolchain.json"))["llvmMajor"])')"
"$LLVM/bin/clang" --version
test -s "$LLVM/lib/libclang.so"
# Prefer the very binaries in the pinned signed packages over Runner aliases.
TOOLS="$(mktemp -d "$RUNNER_TEMP/111-native-tools.XXXXXX")"
for tool in ninja cmake pkg-config openssl; do
  ln -s "/usr/bin/$tool" "$TOOLS/$tool"
  if [ "$tool" = openssl ]; then "$TOOLS/$tool" version; else "$TOOLS/$tool" --version; fi
done
printf 'LLVM_HOME=%s\nLIBCLANG_PATH=%s/lib\n' "$LLVM" "$LLVM" >> "$GITHUB_ENV"
printf '%s/bin\n%s\n' "$LLVM" "$TOOLS" >> "$GITHUB_PATH"
