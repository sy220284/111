#!/usr/bin/env bash
# Keep the exact caller environment after dropping root; no runner cache or implicit HOME.
set -euo pipefail
if [ "$#" -lt 1 ]; then echo "usage: $0 [NAME=value ...] command [args...]" >&2; exit 2; fi
uid="$(id -u)"; gid="$(id -g)"
sudo unshare --net -- bash -c '
    set -euo pipefail
    ip link set lo up
    uid="$1"; gid="$2"; shift 2
    exec setpriv --reuid "$uid" --regid "$gid" --init-groups -- /usr/bin/env -i "$@"
' _ "$uid" "$gid" "HOME=$HOME" "PATH=$PATH" "LANG=C.UTF-8" "$@"
