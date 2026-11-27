package io.github.sy220284.codexandroid

import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Explicit media analysis may establish its real conversation without sending a dummy turn. */
internal suspend fun CodexViewModel.establishMediaAnalysisSource(draftOwner: String, owner: TaskRequestOwner,
    workspaceRoot: File, workspaceTasks: WorkspaceTaskOperations, onCreated: (String) -> Unit) {
    val workspace = activeWorkspace.value
    val project = activeProjectId.value
    val chosenModel = modelId.value
    val options = composerDrafts.value[draftOwner]?.options ?: ComposerTaskOptions()
    val permit = { owner.current(this) && activeThread.value == null && firstThreadStarting.value &&
        composerOwner(null) == draftOwner && conversationState.value.remote == null &&
        modelId.value == chosenModel && !securitySaveBusy.value }
    check(permit()) { "分析所属草稿已变化" }
    CodexForegroundService.startAndAwaitReady(getApplication())
    val config = owner.request(this, "config/read", JSONObject().put("cwd", workspace))
    val submission = ComposerProviderScope.submission(config.getJSONObject("config"), null, chosenModel,
        "default", options) { method, params -> owner.request(this, method, params) }
    val cwd = WorkspaceSelectionContract.validate(workspaceRoot, File(workspace)).absolutePath
    val permission = AnswerWorkspaceStore.permission(workspaceRoot, workspace, ComposerPermissionPolicy.selected(config))
    workspaceTasks.relocateProject(project, owner.epoch)
    check(permit()) { "分析所属草稿已变化" }
    val params = ComposerPermissionPolicy.apply(ProjectRequestContract.threadStart(
        File(cwd), project, submission.model, submission.provider).apply {
            if (AnswerWorkspaceStore.isolated(workspaceRoot, cwd)) put("runtimeWorkspaceRoots", JSONArray().put(cwd))
        }, permission)
    val response = modelPreviews.request("thread/start", params, config, expectedSession = owner.epoch, sendPermit = permit)
    val id = response.getJSONObject("thread").getString("id")
    check(id.isNotBlank() && permit()) { "会话建立结果需核对；原草稿已保留" }
    composerDrafts.update { ThreadComposerDrafts.attachNewThread(it, id, draftOwner) }
    conversationState.update { it.copy(threadId = id, modelProvider = submission.provider) }
    onCreated(id)
    sessions.refreshChanged(id)
}
