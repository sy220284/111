package io.github.sy220284.codexandroid

import android.content.SharedPreferences
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Manages workspace selection and approved plans against the same local Codex session. */
internal class WorkspaceTaskOperations(
    private val vm: CodexViewModel,
    private val workspaceRoot: File,
    private val workspacePreferences: SharedPreferences,
    private val scope: CoroutineScope
) {
    private val activeWorkspace get() = vm.activeWorkspace
    private val activeProjectId get() = vm.activeProjectId
    private val activeThread get() = vm.activeThread
    private val threadSwitching get() = vm.threadSwitching
    private val terminalSession get() = vm.terminalSession
    private val planApprovalBusy get() = vm.planApprovalBusy
    private val selectedMode get() = vm.selectedMode
    private val running get() = vm.running
    private val errors get() = vm.errors
    private val bridge get() = vm.bridge
    private fun sendPrompt(prompt: String) = vm.sendPrompt(prompt)

    /** Resume by the official cwd override; require the server to confirm the real path. */
    suspend fun resumeDirectory(id: String, oldCwd: String, projectId: String): File {
        val selected = WorkspaceSelectionContract.validate(workspaceRoot,
            File(WorkspaceStorage.remap(vm.getApplication(), oldCwd)))
        relocateProject(projectId)
        val params = org.json.JSONObject().put("threadId", id).put("cwd", selected.absolutePath).put("excludeTurns", true)
        val isolated = AnswerWorkspaceStore.isolated(workspaceRoot, selected.absolutePath)
        if (isolated) {
            params.put("runtimeWorkspaceRoots", org.json.JSONArray().put(selected.absolutePath))
            // Loaded running sessions reject permission overrides. Resume the
            // established profile and verify it rather than attempting to change it.
            if (id !in vm.threadExecutions.value) {
                val config = bridge.request("config/read", org.json.JSONObject().put("cwd", selected.absolutePath))
                ComposerPermissionPolicy.apply(params, AnswerWorkspaceStore.permission(workspaceRoot, selected.absolutePath, ComposerPermissionPolicy.selected(config)))
            }
        }
        val resumed = if (id in vm.threadExecutions.value) bridge.request("thread/resume", params)
            else vm.modelPreviews.request("thread/resume", params)
        if (isolated) AnswerWorkspaceStore.verifyRuntime(resumed, selected.absolutePath)
        check(File(resumed.getJSONObject("thread").getString("cwd")).canonicalFile == selected) {
            "运行核心未确认新工作目录，请重新打开会话"
        }
        return selected
    }

    /** Lazily migrate registered roots when an old project is used; preserve all unchanged roots. */
    suspend fun relocateProject(id: String?) {
        if (id.isNullOrBlank()) return
        val roots = bridge.request("project/read", ProjectRequestContract.read(id))
            .getJSONObject("project").getJSONArray("roots")
        val mapped = org.json.JSONArray()
        var changed = false
        for (index in 0 until roots.length()) {
            val path = roots.getJSONObject(index).getString("path")
            val newPath = WorkspaceStorage.remap(vm.getApplication(), path)
            if (newPath != path) {
                WorkspaceSelectionContract.validate(workspaceRoot, File(newPath))
                changed = true
            }
            mapped.put(org.json.JSONObject().put("path", newPath))
        }
        if (!changed) return
        val result = bridge.request("project/update", ProjectRequestContract.relocate(id, mapped))
            .getJSONObject("project").getJSONArray("roots")
        check(result.length() == mapped.length() && (0 until result.length()).all {
            result.getJSONObject(it).getString("path") == mapped.getJSONObject(it).getString("path")
        }) { "项目目录迁移未获运行核心确认" }
        vm.projectCatalogRevision.value++
    }

    /** Switching project starts a new task context; old conversation remains in history. */
    fun selectWorkspace(folder: File) = selectWorkspace(folder, null)
    private fun selectWorkspace(folder: File, projectId: String?) {
        require(vm.globalWorkSnapshot().canStartNewTask && !vm.firstThreadStarting.value && !threadSwitching.value && !running.value && !terminalSession.value.running) {
            "当前有任务或命令正在执行，请先结束后再切换项目"
        }
        val selected = WorkspaceSelectionContract.validate(workspaceRoot,
            File(WorkspaceStorage.remap(vm.getApplication(), folder.absolutePath)))
        if (selected.absolutePath == activeWorkspace.value && activeThread.value == null && activeProjectId.value == projectId) return
        check(vm.resetConversation(selected.absolutePath, projectId)) { "当前无法切换项目，请完成会话操作后重试" }
        workspacePreferences.edit().putString("activeDirectory", selected.absolutePath).apply()
        errors.value = errors.value - "workspace"
    }

    /** Attach an existing Codex project identity to subsequent work and its cwd. */
    fun selectProject(projectId: String, folder: File) {
        require(projectId.isNotBlank()) { "项目编号不可为空" }
        selectWorkspace(folder, projectId)
    }

    /** User-approved plan is prepared by the server before starting execution. */
    fun approvePlan(markdown: String) {
        val threadId = activeThread.value
        if (planApprovalBusy.value || running.value || threadId.isNullOrBlank()) {
            errors.value = errors.value + ("planApproval" to "当前任务状态无法批准方案")
            return
        }
        planApprovalBusy.value = true
        errors.value = errors.value - "planApproval"
        scope.launch {
            try {
                val prepared = bridge.request("thread/prepareApprovedPlan",
                    PlanExecutionContract.prepare(threadId, markdown))
                check(activeThread.value == threadId && !running.value) {
                    "等待方案确认期间会话状态已变化，请重新核对后批准"
                }
                val prompt = PlanExecutionContract.prompt(prepared)
                vm.chooseComposerMode("default")
                sendPrompt(prompt)
            } catch (failure: Exception) {
                errors.value = errors.value + ("planApproval" to
                    (failure.message ?: "方案准备失败，请重试"))
            } finally {
                planApprovalBusy.value = false
            }
        }
    }


}
