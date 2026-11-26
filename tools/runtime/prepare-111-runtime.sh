#!/usr/bin/env bash
# Consume the exact reviewed 777 package set; network access is limited to an explicit producer.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if [ "$#" -ne 3 ]; then echo "usage: $0 <arm64-v8a|x86_64> <runtime-cache> <output>" >&2; exit 2; fi
ABI="$1"; CACHE="$(realpath "$2")"; OUT="$(realpath -m "$3")"
case "$ABI" in arm64-v8a|x86_64) ;; *) exit 2;; esac
test "$OUT" != / && test "$OUT" != "$CACHE" && test ! -e "$OUT"
mkdir -p "$OUT"
export DSH_RUNTIME_ABIS="$ABI"
export DSH_RUNTIME_OFFLINE=true
for component in git node python; do
  test -d "$CACHE/termux-$component"
  TERMUX_RUNTIME_CACHE="$CACHE/termux-$component" bash "$ROOT/tools/runtime/prepare-termux-$component.sh" "$OUT/$component"
done
python3 - "$OUT" "$ABI" "$ROOT/tools/dev/toolchain-lock.json" <<'PY'
import hashlib, json, pathlib, subprocess, sys
out, abi, lock = pathlib.Path(sys.argv[1]), sys.argv[2], json.loads(pathlib.Path(sys.argv[3]).read_text())
v = lock["runtime777"]
versions = {"git": ("git", v["git"]), "node": ("node", v["node"]), "npm": ("node", v["npm"]), "python": ("python", v["python"]), "pip": ("python", v["pip"])}
for tool, (group, expected) in versions.items():
    path = out / group / "assets/runtime" / group / (tool + "-version.txt")
    if path.read_text().strip() != expected:
        raise SystemExit("runtime version drift: " + tool)
for tool, group, package in [("git","git","git"),("curl","git","curl"),("jq","git","jq"),("ripgrep","git","ripgrep"),("node","node","nodejs"),("python","python","python"),("pip","python","python-pip")]:
    path = out / group / "assets/runtime" / group / abi / "packages.tsv"
    records = [line.split("\t") for line in path.read_text().splitlines()]
    rows = [r for r in records if r[0] == package]
    if len(rows) != 1 or rows[0][1] != v[tool]:
        raise SystemExit("locked package drift: " + tool)
    if abi == "arm64-v8a" and tool == "git" and rows[0][3] != v["gitPackageSha256"]:
        raise SystemExit("Git package digest drift")
print(json.dumps({"abi":abi,"versions":{k:v[k] for k in ("git","curl","jq","ripgrep","python","pip","node","npm")},"source":lock["source"]},indent=2))
PY
