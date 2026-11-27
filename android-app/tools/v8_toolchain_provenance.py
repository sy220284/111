#!/usr/bin/env python3
"""Record the actual verified portable LLVM tools before V8 compilation."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess


def provenance(root):
    root = root.resolve(strict=True)
    records = {}
    for name in ("bin/clang", "bin/ninja", "bin/pkg-config", "lib/libclang.so", "packages.tsv"):
        path = (root / name).resolve(strict=True)
        if not path.is_relative_to(root) or not path.is_file() or path.stat().st_size == 0:
            raise ValueError("Invalid portable LLVM input: " + name)
        with path.open("rb") as stream:
            record = {"sha256": hashlib.file_digest(stream, "sha256").hexdigest()}
        if name.startswith("bin/"):
            version = subprocess.check_output([str(path), "--version"], text=True).strip()
            if not version or (name == "bin/clang" and not re.search(r"clang version 21\.", version)):
                raise ValueError("Unexpected portable LLVM tool version: " + name)
            record["version"] = version
        records[name] = record
    records["packages.tsv"]["packages"] = (root / "packages.tsv").read_text()
    return records


if __name__ == "__main__":
    print("portable_toolchain=" + json.dumps(provenance(Path(os.environ["LLVM_HOME"])), sort_keys=True))
