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
        if indent in (0, 4) and re.match(r"^\s*concurrency:\s*$", line):
            skip_indent = indent
            continue
        if indent in (4, 8) and re.match(r"^\s*timeout-minutes:\s*\d+\s*$", line):
            continue
        out.append(line)
    return "\n".join(out)


def job_recipe(workflow, name):
    marker = "\n  " + name + ":\n"
    if marker not in workflow:
        raise ValueError("Missing build job: " + name)
    job = re.split(r"(?m)^  [a-z][a-z0-9-]*:\s*$", workflow.split(marker, 1)[1], maxsplit=1)[0]
    return normalize("\n".join(line for line in job.splitlines()
                               if not re.match(r"^    (?:name|needs|if):", line)))
