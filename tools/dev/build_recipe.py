"""Canonical workflow text for build identity; scheduling controls are not compiler inputs."""
import re

def normalize(text):
    out = []
    skip_indent = None
    for line in text.splitlines():
        indent = len(line) - len(line.lstrip())
        if skip_indent is not None:
            if not line.strip() or indent > skip_indent:
                continue
            skip_indent = None
        if re.match(r"^\s*concurrency:\s*$", line):
            skip_indent = indent
            continue
        if re.match(r"^\s*timeout-minutes:\s*\d+\s*$", line):
            continue
        out.append(line)
    return "\n".join(out)
