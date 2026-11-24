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
    private fun newThread() = vm.newThread()
    private fun sendPrompt(prompt: String) = vm.sendPrompt(prompt)

    /** Switching project starts a new task context; old conversation remains in history. */
    fun selectWorkspace(folder: File) {
        require(vm.globalWorkSnapshot().canStartNewTask && !vm.firstThreadStarting.value && !threadSwitching.value && !running.value && !terminalSession.value.running) {
            "当前有任务或命令正在执行，请先结束后再切换项目"
        }
        val selected = WorkspaceSelectionContract.validate(workspaceRoot, folder)
        if (selected.absolutePath == activeWorkspace.value && activeThread.value == null) return
        if (activeThread.value != null) newThread()
        activeWorkspace.value = selected.absolutePath
        vm.restoreComposerOptions(null)
        activeProjectId.value = null
        workspacePreferences.edit().putString("activeDirectory", selected.absolutePath).apply()
        errors.value = errors.value - "workspace"
    }

    /** Attach an existing Codex project identity to subsequent work and its cwd. */
    fun selectProject(projectId: String, folder: File) {
        require(projectId.isNotBlank()) { "项目编号不可为空" }
        selectWorkspace(folder)
        activeProjectId.value = projectId
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
