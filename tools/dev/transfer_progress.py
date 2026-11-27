"""Bounded immutable artifact download with real byte progress and classified errors."""
import os
import re
import signal
import subprocess
import tempfile
import time
from pathlib import Path

class TransferUnavailable(RuntimeError):
    """Missing, expired or transiently unavailable artifact bytes."""

def stop(process):
    if process.poll() is None:
        try:
            os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            return
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait()

def download(endpoint, destination, expected, *, stall=180, maximum=900, interval=15, attempts=3, command=None):
    destination = Path(destination)
    if type(expected) is not int or expected <= 0:
        raise ValueError("Invalid artifact size")
    if not 1 <= attempts <= 3:
        raise ValueError("Invalid transfer retry budget")
    deadline = time.monotonic() + maximum
    for attempt in range(1, attempts + 1):
        if time.monotonic() >= deadline:
            raise TransferUnavailable("Artifact download exceeded total budget")
        destination.unlink(missing_ok=True)
        print(f"Downloading {endpoint} ({expected} bytes), attempt {attempt}/{attempts}", flush=True)
        with tempfile.TemporaryFile() as errors, destination.open("xb") as output:
            process = subprocess.Popen(command or ["gh", "api", endpoint], stdout=output, stderr=errors, start_new_session=True)
            def interrupted(signum, frame):
                raise SystemExit(128 + signum)
            previous_term = signal.signal(signal.SIGTERM, interrupted)
            start = last_change = last_log = time.monotonic()
            previous = 0
            failure = None
            try:
                while process.poll() is None:
                    now = time.monotonic()
                    size = destination.stat().st_size
                    if size != previous:
                        last_change = now
                        previous = size
                    if size > expected:
                        failure = "Artifact size exceeds descriptor"
                        break
                    if now - last_log >= interval:
                        rate = size / max(0.001, now - start)
                        print(f"Downloaded {size}/{expected} bytes ({100*size/expected:.1f}%), {rate/1048576:.2f} MiB/s", flush=True)
                        last_log = now
                    if now - last_change >= stall or now >= deadline:
                        failure = "Artifact transfer stalled or exceeded time budget"
                        break
                    time.sleep(min(1, interval))
            finally:
                stop(process)
                signal.signal(signal.SIGTERM, previous_term)
            errors.seek(0)
            detail = errors.read(16384).decode("utf-8", errors="replace").strip()
        if process.returncode == 0 and failure is None and destination.stat().st_size == expected:
            print(f"Downloaded {expected}/{expected} bytes", flush=True)
            return
        size = destination.stat().st_size
        destination.unlink(missing_ok=True)
        if failure == "Artifact size exceeds descriptor":
            raise ValueError(failure)
        if failure is None and process.returncode == 0:
            raise ValueError(f"Artifact length mismatch: {size}/{expected}")
        # Authentication/authorization and other permanent API errors must surface.
        if re.search(r"HTTP (?:401|403)\b", detail, re.I) and "rate limit" not in detail.lower():
            raise RuntimeError(f"Artifact authorization failed: {detail}")
        if re.search(r"HTTP (?:404|410)\b", detail, re.I):
            raise TransferUnavailable(f"Artifact unavailable: {detail}")
        if failure is None and not re.search(r"HTTP (?:408|429|5\d\d)\b|timeout|timed out|connection|network|EOF|rate limit|reset|temporar", detail, re.I):
            raise RuntimeError(f"Artifact request failed ({process.returncode}): {detail}")
        message = failure or detail
        print(f"::warning::Artifact transfer attempt {attempt} failed: {message}", flush=True)
        if attempt == attempts or time.monotonic() >= deadline:
            raise TransferUnavailable(message)
        time.sleep(min(attempt * 2, max(0, deadline - time.monotonic())))
