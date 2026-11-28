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
    "TaskRequestOwner.kt":"ARC-02/03 request ownership",
    "ThreadOrganizationController.kt":"ARC-02/03 thread organization",
    "ThreadCopyHistory.kt":"ARC-04 history",
    "ThreadHistoryExport.kt":"ARC-04 history export",
    "ThreadMarkdownExporter.kt":"ARC-04 history rendering",
    "ConversationHistoryController.kt":"ARC-03/04 conversation history",
    "ConversationSelectionController.kt":"ARC-03 conversation selection",
    "SessionListController.kt":"ARC-03 session directory",
    "SecurityPermissionController.kt":"ARC-08 permission",
    "EngineSettingController.kt":"ARC-03 configuration",
}
OWNED_REQUEST_PATHS={
    "ThreadOrganizationController.kt", "ThreadCopyHistory.kt", "ThreadHistoryExport.kt",
    "ThreadHistorySheet.kt", "ConversationHistoryController.kt", "ConversationSelectionController.kt",
    "SessionListController.kt", "SecurityPermissionController.kt", "EngineSettingController.kt",
}
RAW_RPC=re.compile(r"\bbridge\s*\.\s*request\s*\(")
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
    for name in sorted(OWNED_REQUEST_PATHS):
        if name not in sources:
            if name not in OWNERS:
                failures.append(f"ARC-03: 请求责任源码缺失: {name}")
            continue
        if RAW_RPC.search(TRIVIA.sub(" ",sources[name])):
            failures.append(f"ARC-03: {name} 绕过已收敛的请求归属入口")
    return failures
