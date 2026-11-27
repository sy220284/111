#!/usr/bin/env python3
"""Single signed-APT version contract for native CI and portable host tools."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

LOCK = Path(__file__).with_name("native-toolchain.json")

def config():
    return json.loads(LOCK.read_text())

def run(*args):
    subprocess.run([str(x) for x in args],check=True)

def output(*args):
    return subprocess.check_output([str(x) for x in args],text=True)

def require(ok,message):
    if not ok: raise ValueError(message)

def candidate(package):
    match=re.search(r"(?m)^\s*Candidate:\s+(\S+)",output("apt-cache","policy",package))
    require(match and match.group(1)!="(none)","no signed APT candidate: "+package)
    return match.group(1)

def prepare_repository(c):
    identity=dict(line.split("=",1) for line in Path("/etc/os-release").read_text().splitlines() if "=" in line)
    require(identity["ID"].strip('"')=="ubuntu" and identity["VERSION_ID"].strip('"')=="24.04" and os.uname().machine==c["architecture"],"native build host identity mismatch")
    run("sudo","apt-get","update")
    if not re.search(r"(?m)^\s*Candidate:\s+(?!\(none\))\S+",output("apt-cache","policy","clang-"+str(c["llvmMajor"]))):
        with tempfile.TemporaryDirectory() as directory:
            key=Path(directory)/"llvm.asc"
            run("curl","--fail","--location","--retry","3","https://apt.llvm.org/llvm-snapshot.gpg.key","-o",key)
            fingerprints=[line.split(":")[9] for line in output("gpg","--batch","--with-colons","--show-keys",key).splitlines() if line.startswith("fpr:")]
            require(fingerprints and fingerprints[0]==c["keyFingerprint"],"LLVM repository signing key mismatch")
            run("sudo","install","-m","0644",key,"/usr/share/keyrings/111-llvm-snapshot.asc")
            source=Path(directory)/"111-llvm.list"
            source.write_text("deb [signed-by=/usr/share/keyrings/111-llvm-snapshot.asc] "+c["aptRepository"]+" "+c["aptSuite"]+" main\n")
            run("sudo","install","-m","0644",source,"/etc/apt/sources.list.d/111-llvm.list")
            run("sudo","apt-get","update")

def installed(c):
    rows=output("dpkg-query","-W","-f=${Package}\t${Version}\n",*c["packages"]).splitlines()
    return dict(row.split("\t",1) for row in rows)

def verify():
    c=config()
    require(all(isinstance(v,str) and v for v in c["packages"].values()),"native toolchain lock has not been resolved")
    actual=installed(c)
    require(actual==c["packages"],"native package version drift: "+str(actual))
    for name,path in c["executables"].items():
        require(Path(path).is_file(),"missing canonical native tool: "+name)
    return actual

def install():
    c=config()
    require(all(isinstance(v,str) and v for v in c["packages"].values()),"native toolchain lock has not been resolved")
    prepare_repository(c)
    run("sudo","apt-get","install","-y","--no-install-recommends",*[n+"="+v for n,v in c["packages"].items()])
    return verify()

def resolve():
    c=config()
    prepare_repository(c)
    locked={name:prior if prior is not None else candidate(name) for name,prior in c["packages"].items()}
    for name,prior in c["packages"].items():
        require(prior is None or prior==locked[name],"existing approved native version differs: "+name)
    run("sudo","apt-get","install","-y","--no-install-recommends",*[n+"="+v for n,v in locked.items()])
    require(installed(c)==locked,"resolved native package versions do not match installed packages")
    c["packages"]=locked
    LOCK.write_text(json.dumps(c,indent=2)+"\n")
    return verify()

if __name__=="__main__":
    operation=sys.argv[1]
    if operation not in ("resolve","install","verify"): raise SystemExit("usage: native_toolchain.py resolve|install|verify")
    print(json.dumps({"packages":{"resolve":resolve,"install":install,"verify":verify}[operation]()},sort_keys=True))
