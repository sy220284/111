#!/usr/bin/env python3
"""Explicit candidate-only baseline generation from authenticated distribution metadata."""
import base64
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import urllib.request
import zipfile
import email

from portable_host import ROOT, install_python, prepare_node
sys.path.insert(0,str(ROOT/"tools/build"))
import native_toolchain
import npm_archive

def require(ok,message):
    if not ok: raise ValueError(message)

def metadata(url):
    with urllib.request.urlopen(url,timeout=60) as response:
        require(response.url.startswith("https://"),"metadata transport is not HTTPS")
        return json.load(response)

def lock_npm(temp):
    path=npm_archive.LOCK_PATH
    record=json.loads(path.read_text())
    version=record["version"]
    data=metadata("https://registry.npmjs.org/npm/"+version)
    require(data["name"]=="npm" and data["version"]==version,"npm registry version mismatch")
    url="https://registry.npmjs.org/npm/-/npm-"+version+".tgz"
    require(data["dist"]["tarball"]==url,"unexpected npm registry source")
    integrity=data["dist"]["integrity"]
    require(integrity.startswith("sha512-"),"npm registry omitted SHA512 integrity")
    sha=base64.b64decode(integrity[7:],validate=True).hex()
    require(len(sha)==128 and record.get("sha512") in (None,sha),"npm immutable digest mismatch")
    record["sha512"]=sha
    path.write_text(json.dumps(record,indent=2)+"\n")
    npm_archive.fetch(temp)
    node=temp/"node"; node.mkdir()
    prepare_node(node)
    print("Verified npm "+version+" SHA512 "+sha,flush=True)

def lock_python(temp):
    stage=temp/"python-host";stage.mkdir()
    python=install_python(stage)
    source=ROOT/".github/ci/requirements.lock"
    versions={}
    for line in source.read_text().splitlines():
        match=re.fullmatch(r"([a-z0-9-]+)==(\S+) --hash=sha256:[a-f0-9]{64}",line)
        require(match is not None,"unexpected existing Python baseline line")
        versions[match.group(1)]=match.group(2)
    wheels=temp/"wheels";wheels.mkdir()
    subprocess.run([str(python),"-m","pip","download","--only-binary=:all:","--no-deps","--dest",str(wheels),*[k+"=="+v for k,v in versions.items()]],check=True)
    reviewed={}
    for file in sorted(wheels.glob("*.whl")):
        with zipfile.ZipFile(file) as archive:
            names=[n for n in archive.namelist() if n.endswith(".dist-info/METADATA")]
            require(len(names)==1,"missing wheel metadata")
            info=email.message_from_bytes(archive.read(names[0]))
        name=re.sub(r"[-_.]+","-",info["Name"]).lower()
        version=info["Version"]
        require(name in versions and version==versions[name] and name not in reviewed,"unexpected Python distribution")
        data=metadata("https://pypi.org/pypi/"+name+"/"+version+"/json")
        with file.open("rb") as stream:
            sha=hashlib.file_digest(stream,"sha256").hexdigest()
        require(any(a["filename"]==file.name and a["digests"]["sha256"]==sha for a in data["urls"]),"wheel digest differs from PyPI metadata: "+file.name)
        reviewed[name]=sha
    require(set(reviewed)==set(versions),"incomplete CPython 3.14 wheel lock")
    path=source
    path.write_text("".join(f"{name}=={versions[name]} --hash=sha256:{reviewed[name]}\n" for name in sorted(versions)))
    env=temp/"venv"
    subprocess.run([str(python),"-m","venv",str(env)],check=True)
    subprocess.run([str(env/"bin/python"),"-m","pip","install","--no-index","--require-hashes","--find-links",str(wheels),"-r",str(path)],check=True)
    subprocess.run([str(env/"bin/python"),"-m","pip","check"],check=True)
    subprocess.run([str(env/"bin/python"),"-c","import PIL,jsonschema; jsonschema.validate({'ok':True},{'type':'object'})"],check=True)
    print("Verified CPython 3.14 wheels:",sorted(reviewed),flush=True)

if __name__=="__main__":
    with tempfile.TemporaryDirectory() as directory:
        temp=Path(directory)
        lock_npm(temp)
        lock_python(temp)
        print("Verified signed APT package versions:",native_toolchain.resolve(),flush=True)
