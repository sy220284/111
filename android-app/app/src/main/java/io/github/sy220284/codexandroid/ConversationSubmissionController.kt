package io.github.sy220284.codexandroid

import android.app.Application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Owns prompt admission, the first-turn reservation and the native submission lifecycle. */
internal class ConversationSubmissionController(
    private val vm: CodexViewModel,
    private val workspaceRoot: File,
    private val workspaceTasks: WorkspaceTaskOperations
) {
    private var firstSubmission: Any? = null
    var reservedImages: List<String> = emptyList()
        private set

    private fun releaseFirstSubmission() {
        firstSubmission = null
        reservedImages = emptyList()
        vm.firstThreadStarting.value = false
    }

    fun send(text: String, localImages: List<String>, references: List<ComposerReference>,
        afterAccepted: (String) -> Unit) {
        with(vm) {
            if (securitySaveBusy.value) {
                errors.value = errors.value + ("conversation" to "正在保存权限，请完成后再发送指令")
                return
            }
            if (!globalWorkSnapshot().canStartNewTask) {
                errors.value = errors.value + ("conversation" to
                    "本机数据正在备份或恢复，完成后再发送指令")
                return
            }
            if (connectionRestarter.busy.value) { errors.value = errors.value + ("conversation" to "正在重新连接内核，请完成后发送"); return }
            val prompt = text.trim()
            if (activeThread.value?.let(vm::answerFamilyBusy) == true) {
                errors.value = errors.value + ("conversation" to "正在整理会话，请完成后再发送指令")
                return
            }
            if (threadSwitching.value) {
                errors.value = errors.value + ("conversation" to "正在恢复会话，请在完成后发送指令")
                return
            }
            val owner = captureTaskOwner()
            if (owner == null) {
                errors.value = errors.value + ("conversation" to "运行核心尚未连接，草稿已保留")
                return
            }
            val epoch = owner.epoch
            val permit = { owner.current(this) }
            val submittedThread = activeThread.value
            if ((prompt.isEmpty() && localImages.isEmpty() && references.isEmpty()) || running.value ||
                (submittedThread == null && firstThreadStarting.value) ||
                (submittedThread != null && submittedThread in threadExecutions.value)) return
            val images = localImages.toList()
            val submission = Any()
            // Own the destination synchronously; a second tap on this thread cannot
            // submit twice and switching to another thread does not transfer the turn.
            if (submittedThread == null) {
                firstSubmission = submission
                reservedImages = images
                firstThreadStarting.value = true
            } else {
                threadExecutions.value = ThreadExecutionPolicy.reserve(
                    threadExecutions.value, submittedThread, images, submission)
                threadFailures.value = threadFailures.value - submittedThread
                threadActivities.value = ThreadActivityPolicy.updated(
                    threadActivities.value, submittedThread, ThreadActivity.Running)
            }
            running.value = true
            val chosenModel = modelId.value
            val chosenMode = selectedMode.value
            val chosenWorkspace = activeWorkspace.value
            val chosenRemote = conversationState.value.remote
            val chosenThreadProvider = activeModelProvider.value.takeIf { submittedThread != null }
            val draftOwner = composerOwner(submittedThread, chosenWorkspace)
            val chosenOptions = composerDrafts.value[draftOwner]?.options ?: ComposerTaskOptions()
            val chosenEffort = chosenOptions.effort
            val chosenProject = activeProjectId.value
            viewModelScope.launch {
                var newlyCreatedThreadId: String? = null
                var submittedProvider: String? = null
                var turnRequestDispatched = false
                val pendingId = "pending-" + UUID.randomUUID()
                if (activeThread.value == submittedThread) timeline.value = timeline.value + TimelineItem(
                    pendingId, "user",
                    if (images.isEmpty()) prompt else
                        listOf(prompt, LocalMediaAttachmentPolicy.summary(images)).filter { it.isNotBlank() }.joinToString("\n"),
                    "sending")
                try {
                    // Android must confirm foreground mode before any turn RPC is sent.
                    CodexForegroundService.startAndAwaitReady(getApplication<Application>())
                    require(chosenMode == "default" || chosenMode == "plan") {
                        "未知协作方式：" + chosenMode
                    }
                    chosenRemote?.requireTextOnly(images, references)
                    check(bridge.connectedSession.value == epoch && permit()) { "连接或账号已变化，草稿已保留" }
                    if (chosenRemote != null) {
                        if (submittedThread == null) remoteBindings.ready(epoch, chosenRemote) { method, params ->
                            owner.request(vm, method, params)
                        } else ensureRemoteThread(submittedThread, chosenRemote, expectedSession = epoch, sendPermit = permit)
                    }
                    val permissionConfig = owner.request(vm, "config/read", JSONObject().put("cwd", chosenWorkspace))
                    val chosenPermission = AnswerWorkspaceStore.permission(workspaceRoot, chosenWorkspace, ComposerPermissionPolicy.selected(permissionConfig))
                    val (provider, requestModel, catalog, effectiveConfig) = ComposerProviderScope.submission(
                        permissionConfig.getJSONObject("config"), submittedThread, chosenModel, chosenMode,
                        chosenOptions, mediaKinds = LocalMediaAttachmentPolicy.modalities(images),
                        knownThreadProvider = chosenThreadProvider) { method, params -> owner.request(vm, method, params) }
                    submittedProvider = provider
                    AppDataTransfer.withLocalMutation { LocalMediaAttachmentPolicy.prepare(images, LocalImageAttachments.directory(getApplication<Application>())) { owner.requireCurrent(vm) } }
                    ComposerInputPolicy.validate(images, references, LocalImageAttachments.directory(getApplication<Application>()), File(chosenWorkspace))
                    owner.requireCurrent(vm)
                    if (submittedThread == null && chosenRemote == null) workspaceTasks.relocateProject(chosenProject, owner)
                    if (chosenRemote == null) captureAnswerBaseline(pendingId.removePrefix("pending-"), chosenWorkspace, submittedThread)
                    LocalImageAttachments.preserveForHistory(getApplication<Application>(), images, pendingId.removePrefix("pending-"))
                    val outcome = TurnSubmissionCoordinator { method, params ->
                        check(permit()) { "账号已变化，未继续提交任务" }
                        if (method == "thread/start" && chosenRemote != null) {
                            val response = owner.guarded(permit) {
                                bridge.request(method, chosenRemote.apply(params), sendPermit = permit, expectedSession = epoch)
                            }
                            val created = response.getJSONObject("thread")
                            remoteBindings.bind(created.getString("id"), chosenRemote)
                            check(chosenRemote.matches(created)) { "内核没有确认远端会话，未发送任务" }
                            response
                        } else if (method == "thread/start") modelPreviews.request(method, params, permissionConfig, expectedSession = epoch, sendPermit = permit)
                        else owner.guarded(permit) {
                            bridge.request(method, if (chosenRemote != null && method == "turn/start") chosenRemote.apply(params) else params, sendPermit = permit, expectedSession = epoch)
                        }
                    }.submit(
                        existingThreadId = submittedThread,
                        createThreadParams = {
                            val cwd = WorkspaceSelectionContract.validate(
                                workspaceRoot, File(chosenWorkspace)).absolutePath
                            ComposerPermissionPolicy.apply(ProjectRequestContract.threadStart(
                                File(cwd), chosenProject, requestModel, provider).apply { if (AnswerWorkspaceStore.isolated(workspaceRoot, cwd)) put("runtimeWorkspaceRoots", JSONArray().put(cwd)) }, chosenPermission)
                        },
                        turnParams = { id ->
                            ComposerPermissionPolicy.apply(TurnRequestContract.build(
                                threadId = id, text = prompt, modelId = requestModel,
                                mode = chosenMode, catalog = catalog, localImages = images,
                                effort = chosenEffort, references = references, effectiveConfig = effectiveConfig
                            ).put("clientUserMessageId", pendingId.removePrefix("pending-")).apply { if (AnswerWorkspaceStore.isolated(workspaceRoot, chosenWorkspace)) put("cwd", chosenWorkspace).put("runtimeWorkspaceRoots", JSONArray().put(chosenWorkspace)) }, chosenPermission)
                        },
                        onThreadCreated = { createdId ->
                            owner.requireCurrent(vm)
                            check(firstSubmission === submission) { "原新建任务已结束" }
                            newlyCreatedThreadId = createdId
                            threadExecutions.value = ThreadExecutionPolicy.reserve(
                                threadExecutions.value, createdId, images, submission)
                            threadFailures.value = threadFailures.value - createdId
                            threadActivities.value = ThreadActivityPolicy.updated(
                                threadActivities.value, createdId, ThreadActivity.Running)
                            releaseFirstSubmission()
                            composerDrafts.update {
                                ThreadComposerDrafts.attachNewThread(it, createdId, draftOwner)
                            }
                            conversationState.update { it.copy(threadId = createdId, modelProvider = provider) }
                            sessions.refreshChanged(createdId)
                        }
                    )
                    when (outcome) {
                        is TurnSubmissionOutcome.Accepted -> {
                            turnRequestDispatched = true
                            val id = outcome.threadId
                            if (threadExecutions.value[id]?.submission === submission) {
                                threadExecutions.value = ThreadExecutionPolicy.started(
                                    threadExecutions.value, id, outcome.turnId)
                                if (activeThread.value == id) {
                                    conversationState.update { it.copy(turnId = threadExecutions.value[id]?.turnId, running = true) }
                                    errors.value = errors.value - "conversation"
                                }
                            }
                            if (owner.current(vm) &&
                                (threadExecutions.value[id]?.submission === submission || id !in threadExecutions.value))
                                afterAccepted(id)
                        }
                        is TurnSubmissionOutcome.Failed -> {
                            newlyCreatedThreadId = outcome.newlyCreatedThreadId
                            turnRequestDispatched = outcome.turnRequestDispatched
                            throw outcome.failure
                        }
                    }
                } catch (failure: Exception) {
                    // An ambiguous timeout retains this thread's ownership and images:
                    // the core may have accepted the turn while its response was lost.
                    val definiteFailure = !turnRequestDispatched ||
                        FirstTurnRecoveryPolicy.explicitlyRejected(failure)
                    val submittedId = newlyCreatedThreadId ?: submittedThread
                    val ownsSubmission = if (submittedId == null) firstSubmission === submission
                        else threadExecutions.value[submittedId]?.submission === submission
                    if (firstSubmission === submission) releaseFirstSubmission()
                    if (!ownsSubmission) return@launch
                    if (definiteFailure && submittedId != null) {
                        threadExecutions.value = ThreadExecutionPolicy.finish(
                            threadExecutions.value, submittedId)
                        threadActivities.value = ThreadActivityPolicy.updated(
                            threadActivities.value, submittedId, ThreadActivity.Failed)
                    }
                    var recoveryNote = ""
                    val created = newlyCreatedThreadId
                    // Reclaim an empty newly created session only after an explicit
                    // server rejection. Never delete after timeout/disconnect:
                    // the server may already have accepted the turn.
                    if (created != null && owner.current(vm) &&
                        FirstTurnRecoveryPolicy.explicitlyRejected(failure)) {
                        try {
                            recoveryNote = FirstTurnRecoveryController { method, params, timeout, deletePermit ->
                                owner.request(vm, method, params, timeout, deletePermit)
                            }.reclaim(created, { activeThread.value }, {
                                activeTurn.value ?: threadExecutions.value[created]?.let { "reserved" }
                            }) {
                                owner.requireCurrent(vm)
                                composerDrafts.update {
                                    ThreadComposerDrafts.reclaimRejectedNewThread(it, created, draftOwner)
                                }
                                if (activeThread.value == created && activeTurn.value.isNullOrBlank() &&
                                    created !in threadExecutions.value) {
                                    restoreComposerOptions(null)
                                    activeThread.value = null
                                }
                                sessions.refreshChanged(created)
                            }
                        } catch (recoveryFailure: Exception) {
                            recoveryNote = "；新建会话检查失败：" +
                                (recoveryFailure.message ?: "请到侧栏会话列表核查")
                        }
                    }
                    if (submittedId != null && threadExecutions.value[submittedId]?.submission?.let { it !== submission } == true)
                        return@launch
                    if (!owner.current(vm)) return@launch
                    if (activeThread.value == submittedId ||
                        (submittedThread == null && activeThread.value == null && firstSubmission == null)) {
                        running.value = !definiteFailure && submittedId != null &&
                            submittedId in threadExecutions.value
                        if (definiteFailure) activeTurn.value = null
                        timeline.value = timeline.value.filterNot { it.id == pendingId }
                    }
                    val message = SubmissionFailurePolicy.message(
                        failure, turnRequestDispatched) + recoveryNote
                    if (submittedId != null) {
                        threadFailures.value = ThreadExecutionPolicy.failureUpdated(
                            threadFailures.value, submittedId, message)
                    }
                    if (owner.current(vm) && ComposerProviderScope.isModelRejection(
                            message, (failure as? RpcServerRejection)?.codexErrorInfo)) {
                        catalogWarmup.modelRejected(submittedId, submittedProvider)
                    }
                    if (activeThread.value == submittedId) {
                        errors.value = errors.value + ("conversation" to message)
                        timeline.value = timeline.value + TimelineItem(
                            "failure-" + UUID.randomUUID(), "error", message)
                    }
                }
            }
        }
    }

    fun connectionLost() = with(vm) {
        if (running.value || threadExecutions.value.isNotEmpty() || firstThreadStarting.value) {
            val wasVisible = running.value
            threadExecutions.value.keys.forEach { id ->
                threadActivities.value = ThreadActivityPolicy.updated(
                    threadActivities.value, id, ThreadActivity.Failed)
            }
            threadExecutions.value = emptyMap()
            releaseFirstSubmission()
            conversationState.update { state ->
                val retained = when {
                    !wasVisible -> state.timeline
                    !state.turnId.isNullOrBlank() -> TimelineEventReducer.connectionLost(state.timeline, state.turnId)
                    else -> state.timeline + TimelineItem("disconnect-" + UUID.randomUUID(), "error",
                        "运行核心已断开。已保留会话记录和未发送指令，请重新连接后核查任务结果。")
                }
                state.copy(running = false, turnId = null, timeline = IndexedTimeline.from(retained))
            }
        }
    }
}
