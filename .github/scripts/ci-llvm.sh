#!/usr/bin/env bash
set -euo pipefail
PACKAGES=(clang-23 libclang-23-dev llvm-23 lld-23 ninja-build pkg-config libssl-dev)
missing=()
for p in "${PACKAGES[@]}"; do
  if ! dpkg-query -W -f='${db:Status-Abbrev}' "$p" 2>/dev/null | grep -q '^ii '; then missing+=("$p"); fi
done
if [ "${#missing[@]}" -gt 0 ]; then
  sudo apt-get update
  if ! apt-cache policy clang-23 | grep -Eq 'Candidate: [^ (]'; then
    key="$(mktemp)"
    trap 'rm -f "$key"' EXIT
    curl --fail --location --retry 3 https://apt.llvm.org/llvm-snapshot.gpg.key -o "$key"
    fingerprint="$(gpg --batch --with-colons --show-keys "$key" | awk -F: '$1=="fpr" && !found {print $10; found=1}')"
    test "$fingerprint" = 6084F3CF814B57C1CF12EFD515CF4D18AF4F7421
    sudo install -m 0644 "$key" /usr/share/keyrings/111-llvm-snapshot.asc
    echo 'deb [signed-by=/usr/share/keyrings/111-llvm-snapshot.asc] https://apt.llvm.org/noble/ llvm-toolchain-noble-23 main' | sudo tee /etc/apt/sources.list.d/111-llvm.list >/dev/null
    sudo apt-get update
  fi
  sudo apt-get install -y --no-install-recommends "${missing[@]}"
fi
LLVM=/usr/lib/llvm-23
"$LLVM/bin/clang" --version | grep -E 'clang version 23\.'
test -s "$LLVM/lib/libclang.so"
command -v ninja
command -v pkg-config
printf 'LLVM_HOME=%s\nLIBCLANG_PATH=%s/lib\n' "$LLVM" "$LLVM" >> "$GITHUB_ENV"
printf '%s/bin\n' "$LLVM" >> "$GITHUB_PATH"
