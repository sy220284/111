"""One read-only snapshot for all static Android audit rules."""
from functools import cached_property
import json
from pathlib import Path
from protocol_contract import parse_contracts, parse_notifications
from ui_evidence_manifest import MANIFEST_RELATIVE_PATH

APP=Path("android-app/app/src")
PRODUCTION=APP/"main/java"
KOTLIN_PACKAGE=PRODUCTION/"io/github/sy220284/codexandroid"
OFFICIAL=Path("upstream/codex/codex-rs/app-server-protocol")
POLICY=Path("android-app/tools/ui_surface_policy.json")

class AuditInputs:
    def __init__(self,root):
        self.root=Path(root).resolve()
        self._texts={}
        self._kotlin={}

    def text(self,path):
        path=(self.root/path).resolve()
        if path not in self._texts:
            self._texts[path]=path.read_text(encoding="utf-8")
        return self._texts[path]

    def kotlin(self,folder):
        folder=(self.root/folder).resolve()
        if folder not in self._kotlin:
            self._kotlin[folder]={p:self.text(p) for p in sorted(folder.rglob("*.kt"))}
        return self._kotlin[folder]

    @staticmethod
    def names(files,*,stems=False):
        result={}
        for path,code in files.items():
            name=path.stem if stems else path.name
            if name in result:
                raise ValueError("Duplicate Kotlin source identity: "+name)
            result[name]=code
        return result

    @cached_property
    def production(self):
        return self.kotlin(PRODUCTION)

    @cached_property
    def protocol(self):
        return self.text(OFFICIAL/"src/protocol/common.rs")

    @cached_property
    def contracts(self):
        return parse_contracts(self.protocol)

    @cached_property
    def notifications(self):
        return parse_notifications(self.protocol)

    @cached_property
    def policy_text(self):
        return self.text(POLICY)

    @cached_property
    def policy(self):
        return json.loads(self.policy_text)

    @cached_property
    def manifest(self):
        return json.loads(self.text(MANIFEST_RELATIVE_PATH))

    @cached_property
    def all_tests(self):
        return {**self.kotlin(APP/"test"),**self.kotlin(APP/"androidTest")}
