#!/usr/bin/env python3
"""Produce relocatable host distributions from immutable upstream checksums."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[2]
PROJECT = json.loads((ROOT/"tools/dev/toolchain-lock.json").read_text())["project"]
sys.path.insert(0,str(ROOT/"tools/runtime"))
import npm_archive

def require(ok,message):
    if not ok: raise ValueError(message)

def output(*args,env=None):
    return subprocess.check_output([str(a) for a in args],text=True,env=env).strip()

def official_archive(record,folder):
    archive=folder/"distribution.tar"
    subprocess.run(["curl","--fail","--silent","--show-error","--location","--retry","3","--proto","=https","--proto-redir","=https",record["url"],"-o",str(archive)],check=True)
    with archive.open("rb") as stream:
        require(hashlib.file_digest(stream,"sha256").hexdigest()==record["sha256"],"official host distribution digest mismatch")
    target=folder/"extract"; target.mkdir()
    with tarfile.open(archive,"r:*") as tar:
        tar.extractall(target,filter="data")
    children=list(target.iterdir())
    require(len(children)==1 and children[0].is_dir(),"unexpected official distribution root")
    return children[0]

def install_python(stage):
    with tempfile.TemporaryDirectory() as directory:
        source=official_archive(PROJECT["hostPythonDistribution"],Path(directory))
        shutil.copytree(source,stage/"python",symlinks=True)
    return verify_python(stage)

def verify_python(stage):
    python=stage/"python/bin/python3"
    require(output(python,"-c","import platform; print(platform.python_version())")==PROJECT["hostPython"],"host Python version mismatch")
    return python

def prepare_node(stage):
    with tempfile.TemporaryDirectory() as directory:
        folder=Path(directory)
        source=official_archive(PROJECT["hostNode"],folder)
        shutil.copytree(source,stage,dirs_exist_ok=True,symlinks=True)
        archive=npm_archive.fetch(folder)
        npm_stage=folder/"npm"; npm_stage.mkdir()
        with tarfile.open(archive,"r:gz") as tar:
            tar.extractall(npm_stage,filter="data")
        package=npm_stage/"package"
        require(json.loads((package/"package.json").read_text())["version"]==npm_archive.spec()["version"],"npm archive version mismatch")
        destination=stage/"lib/node_modules/npm"
        shutil.rmtree(destination)
        shutil.copytree(package,destination,symlinks=True)
        for command in ("npm","npx"):
            launcher=stage/"bin"/command
            launcher.unlink()
            launcher.symlink_to("../lib/node_modules/npm/bin/"+command+"-cli.js")
    verify_node(stage)

def verify_node(stage):
    node=stage/"bin/node"
    env=dict(os.environ,PATH=str(node.parent)+os.pathsep+os.environ["PATH"])
    require(output(node,"--version",env=env)=="v"+PROJECT["hostNode"]["version"],"host Node version mismatch")
    require(output(node,stage/"bin/npm","--version",env=env)==npm_archive.spec()["version"],"host npm version mismatch")
    require(output(node,stage/"bin/npx","--version",env=env)==npm_archive.spec()["version"],"host npx version mismatch")
    with tempfile.TemporaryDirectory() as directory:
        root=Path(directory)
        (root/"package.json").write_text('{"name":"offline-probe","version":"1.0.0","private":true}')
        subprocess.run([str(node),str(stage/"bin/npm"),"install","--offline","--ignore-scripts","--no-audit","--no-fund"],cwd=root,env=env,check=True)
