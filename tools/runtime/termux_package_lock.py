#!/usr/bin/env python3
"""Check selected Termux packages against the reviewed dual-architecture lock."""
import json
from pathlib import Path
import sys

LOCK = Path(__file__).with_name("termux-packages.json")


def package_records(index, names):
    records = {}
    for stanza in index.read_text().split("\n\n"):
        if not stanza.startswith("Package: "):
            continue
        fields = dict(line.split(": ", 1) for line in stanza.splitlines()
                      if not line.startswith((" ", "\t")) and ": " in line)
        name = fields["Package"]
        if name not in names:
            continue
        if name in records:
            raise ValueError(f"Duplicate package in Termux index: {name}")
        records[name] = {name: fields[key] for name, key in
                         (("version", "Version"), ("file", "Filename"), ("sha256", "SHA256"))}
    return records


def verify(component, arch, index, packages):
    locked = json.loads(LOCK.read_text())
    expected_names = locked["selection"][arch][component]
    if sorted(packages) != expected_names:
        raise ValueError(f"Termux {arch}/{component} dependency set changed")
    snapshot = package_records(index, set(expected_names))
    changed = {}
    for name in expected_names:
        expected = locked["packages"][arch][name]
        actual = snapshot.get(name)
        if actual != expected:
            changed[name] = {"locked": expected, "signedIndex": actual}
    if changed:
        raise ValueError(f"Termux {arch}/{component} locked packages changed: "
                         + json.dumps(changed, sort_keys=True))


if __name__ == "__main__":
    component, arch, index, *packages = sys.argv[1:]
    verify(component, arch, Path(index), packages)
