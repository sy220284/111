#!/usr/bin/env python3
"""Android build adapter: retain wire diagnostics without recording audio/text payloads."""
from pathlib import Path
import sys

REPLACEMENTS = (
    ('trace!(target: REALTIME_WIRE_LOG_TARGET, "realtime websocket request: {payload}");',
     'trace!(target: REALTIME_WIRE_LOG_TARGET, bytes = payload.len(), "realtime websocket request");', 2),
    ('debug!(?message, "realtime websocket request");',
     'debug!("realtime websocket request encoded");', 1),
    ('trace!(target: REALTIME_WIRE_LOG_TARGET, "realtime websocket event: {text}");',
     'trace!(target: REALTIME_WIRE_LOG_TARGET, bytes = text.len(), "realtime websocket event");', 1),
    ('debug!(?event, "realtime websocket parsed event");',
     'debug!("realtime websocket parsed event");', 1),
)

def adapt(source):
    for original, safe, count in REPLACEMENTS:
        if source.count(original) == count and source.count(safe) == 0:
            source = source.replace(original, safe)
        elif source.count(original) == 0 and source.count(safe) == count:
            continue
        else:
            raise ValueError("Realtime log contract changed: " + original)
    return source

if __name__ == "__main__":
    path = Path(sys.argv[1])
    original = path.read_text(encoding="utf-8")
    updated = adapt(original)
    if updated != original:
        path.write_text(updated, encoding="utf-8")
