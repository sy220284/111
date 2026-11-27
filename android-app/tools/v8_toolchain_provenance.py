#!/usr/bin/env python3
"""Record the official host LLVM tools and binary digests."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

def provenance(root):
    records = {}
    for name, path in {"clang":root/"bin/clang", "ninja":Path(shutil.which("ninja")),
                       "pkg-config":Path(shutil.which("pkg-config")), "libclang":root/"lib/libclang.so"}.items():
        path=path.resolve(strict=True)
        if not path.is_file() or path.stat().st_size == 0:
            raise ValueError("Invalid LLVM input: "+name)
        with path.open("rb") as stream:
            record={"sha256":hashlib.file_digest(stream,"sha256").hexdigest()}
        if name!="libclang":
            version=subprocess.check_output([str(path),"--version"],text=True).strip()
            if not version or name=="clang" and not re.search(r"clang version 23\.",version):
                raise ValueError("Unexpected LLVM tool version: "+name)
            record["version"]=version
        records[name]=record
    records["packages"]=subprocess.check_output(["dpkg-query","-W","-f=${Package}\\t${Version}\\n","clang-23","libclang-23-dev","llvm-23","lld-23","ninja-build","pkg-config","libssl-dev"],text=True)
    return records

if __name__=="__main__":
    print("host_toolchain="+json.dumps(provenance(Path(os.environ["LLVM_HOME"])),sort_keys=True))
