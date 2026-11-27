"""Enforce already-established core/UI boundaries; avoid claiming target modules already exist."""
import re
OWNERS={
    "CodexConnection.kt":"ARC-02/03 transport",
    "RuntimeSupervisor.kt":"ARC-02 runtime",
    "RuntimePayloadIntegrity.kt":"ARC-08 integrity",
    "SessionIndexStore.kt":"ARC-04 storage",
    "UpdateManifest.kt":"ARC-02 update protocol",
    "RemoteTransferCheckpoint.kt":"ARC-04/08 transfer",
    "BackupCheckpointValidation.kt":"ARC-04/08 backup",
}
UI_IMPORT=re.compile(r"(?m)^\s*import\s+androidx\.(?:compose|navigation)(?:\.|$)")
UI_QUALIFIED=re.compile(r"\bandroidx\.(?:compose|navigation)\.")
TRIVIA=re.compile(r'"""[\s\S]*?"""|/\*[\s\S]*?\*/|//[^\n]*|"(?:\\.|[^"\\])*"')
def check(sources):
    failures=[]
    for name,rule in OWNERS.items():
        if name not in sources:
            failures.append(f"{rule}: 审计责任源码缺失: {name}")
            continue
        code=TRIVIA.sub(" ",sources[name])
        if UI_IMPORT.search(code) or UI_QUALIFIED.search(code):
            failures.append(f"{rule}: {name} 反向依赖 Compose/导航")
    return failures
