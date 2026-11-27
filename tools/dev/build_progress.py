#!/usr/bin/env python3
"""Run a build with live output and a hard no-output timeout; no blind retries."""
import argparse
import os
import selectors
import signal
import subprocess
import sys
import time

def run(command, idle=1800, maximum=10800):
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, start_new_session=True)
    selector = selectors.DefaultSelector()
    selector.register(process.stdout, selectors.EVENT_READ)
    started = last = time.monotonic()
    def terminate(signum, frame):
        raise SystemExit(128 + signum)
    old = signal.signal(signal.SIGTERM, terminate)
    try:
        while True:
            now = time.monotonic()
            if now - last >= idle or now - started >= maximum:
                print("::error::Build exceeded its no-output or total time budget", flush=True)
                return 124
            for key, _ in selector.select(timeout=min(1, idle, maximum)):
                data = os.read(key.fileobj.fileno(), 65536)
                if data:
                    sys.stdout.buffer.write(data)
                    sys.stdout.buffer.flush()
                    last = time.monotonic()
                else:
                    selector.unregister(key.fileobj)
            if process.poll() is not None and not selector.get_map():
                return process.returncode
    finally:
        selector.close()
        if process.poll() is None:
            try:
                os.killpg(process.pid, signal.SIGTERM)
                process.wait(timeout=10)
            except ProcessLookupError:
                pass
            except subprocess.TimeoutExpired:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                process.wait()
        process.stdout.close()
        signal.signal(signal.SIGTERM, old)

if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--idle", type=int, default=1800)
    p.add_argument("--maximum", type=int, default=10800)
    p.add_argument("command", nargs=argparse.REMAINDER)
    a = p.parse_args()
    command = a.command[1:] if a.command[:1] == ["--"] else a.command
    if not command or min(a.idle, a.maximum) <= 0:
        p.error("A command and positive limits are required")
    raise SystemExit(run(command, a.idle, a.maximum))
