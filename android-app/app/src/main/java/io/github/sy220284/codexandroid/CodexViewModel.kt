package io.github.sy220284.codexandroid

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class CodexViewModel(application: Application) : AndroidViewModel(application) {
    // Owned by CodexApplication for the entire process, shared by Activity and foreground Service.
    val bridge = CodexConnection(application.applicationContext)
    internal val connectionRestarter by lazy { ConnectionRestartController(this) }
    internal val realtimeVoiceLease = RealtimeVoiceLease()
    internal val domesticProviders by lazy { DomesticProviderController(this) }
    internal val approvalAudit = ApprovalAuditController(application.applicationContext)
    private val workspaceRoot = WorkspaceStorage.root(application)
    val workingDirectory = workspaceRoot.absolutePath
    internal val modelPreviews = ModelPreviewController(this, workspaceRoot)
    private val workspacePreferences = application.getSharedPreferences("codex-workspace", 0)
    private val restoredWorkspace = WorkspaceSelectionContract.restore(
        workspaceRoot, workspacePreferences.getString("activeDirectory", null)?.let { WorkspaceStorage.remap(application, it) })
    internal val conversationState = MutableStateFlow(ActiveConversationContext(restoredWorkspace.directory.absolutePath))
    internal val remoteBindings = RemoteExecutionBindings(application.getSharedPreferences("codex-execution-targets", 0))
    val activeWorkspace = ConversationField(conversationState, { it.workspace }) { s, v -> s.copy(workspace = v) }
    val activeProjectId = ConversationField(conversationState, { it.projectId }) { s, v -> s.copy(projectId = v) }
    val projectCatalogRevision = MutableStateFlow(0)
    val planApprovalBusy = MutableStateFlow(false)
    val activeThread = ConversationField(conversationState, { it.threadId }) { s, v -> s.copy(threadId = v) }
    internal val activeModelProvider = ConversationField(conversationState, { it.modelProvider }) { s, v -> s.copy(modelProvider = v) }
    internal val threadActivities = MutableStateFlow<Map<String, ThreadActivity>>(emptyMap())
    val threadSwitching = MutableStateFlow(false)
    /** True across the entire permissions save + effective-config readback. */
    val securitySaveBusy = MutableStateFlow(false)
    /** Reserved before asynchronous foreground-service startup for terminal commands. */
    val terminalStarting = MutableStateFlow(false)
    val activeTurn = ConversationField(conversationState, { it.turnId }) { s, v -> s.copy(turnId = v) }
    val running = ConversationField(conversationState, { it.running }) { s, v -> s.copy(running = v) }
    /** All running threads, including tasks no longer visible on the current page. */
    internal val threadExecutions = MutableStateFlow<Map<String, ThreadExecution>>(emptyMap())
    val threadFailures = MutableStateFlow<Map<String, String>>(emptyMap())
    /** The first RPC must create a real thread ID before we can switch away. */
    val firstThreadStarting = MutableStateFlow(false)
    val timeline = ConversationField(conversationState, { it.timeline }) { s, v -> s.copy(timeline = IndexedTimeline.from(v)) }
    internal val timelineWindow = TimelineWindowStore(File(application.cacheDir, "codex-timeline-window.jsonl"))
    val olderTimelineCount get() = timelineWindow.olderCount
    internal val olderServerCursor = MutableStateFlow<String?>(null)
    internal var historyTurnOutcomes: HistoryTurnOutcomes? = null
    internal val loadingEarlier = MutableStateFlow(false)
    internal var hydratingThread: String? = null
    internal var timelineEpoch = 0L
    internal val timelineCompaction = MutableStateFlow(false)
    internal var timelineCompacting: Boolean
        get() = timelineCompaction.value
        set(value) { timelineCompaction.value = value }
    internal val liveTimelines = ConversationLiveTimeline()
    fun loadEarlierTimeline() = loadEarlierHistory()
    private val draftStorage = DraftStorageController(application.applicationContext, viewModelScope)
    internal val agentDraftWorkspace = MutableStateFlow<String?>(null)
    internal fun composerOwner(threadId: String?, workspace: String = activeWorkspace.value): String =
        if (threadId == null && conversationState.value.remote != null)
            ThreadComposerDrafts.key(null, "remote:" + conversationState.value.remote!!.json().toString())
        else if (threadId == null && agentDraftWorkspace.value == workspace) ThreadComposerDrafts.agentKey(workspace)
        else ThreadComposerDrafts.key(threadId, workspace)
    val composerDrafts = draftStorage.drafts.also { flow ->
        flow.update { ThreadComposerDrafts.reclaimRejectedNewThread(it, ThreadComposerDrafts.NEW_THREAD, composerOwner(null)) }
    }
    // Task text is edited on Main and must be visible to Compose in the same
    // input transaction. An asynchronous Flow projection may lag the IME edit.
    internal val asyncReplyReceipts = AsyncReplyReceipts()
    private val taskPanelDrafts = mutableStateMapOf<String, TaskPanelDraft>()
    internal fun taskPanelDraft(threadId: String?): TaskPanelDraft =
        threadId?.let { taskPanelDrafts[it] } ?: TaskPanelDraft()

    @androidx.annotation.MainThread
    internal fun editTaskPanelDraft(threadId: String?, edit: (TaskPanelDraft) -> TaskPanelDraft) {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
        val id = threadId?.takeIf(String::isNotBlank) ?: return
        val current = taskPanelDrafts.toMap()
        val next = TaskPanelDraftPolicy.change(current, id, edit)
        if (next === current) return
        val draft = next[id]
        if (draft == null) taskPanelDrafts.remove(id) else taskPanelDrafts[id] = draft
    }
    private val attachmentController by lazy {
        AttachmentImportController(getApplication(), viewModelScope, composerDrafts, errors) {
            threadExecutions.value.values.flatMap { it.images } + firstTurnImages + pendingComposerImages.value
        }
    }
    val attachmentImport get() = attachmentController.state
    internal val composerContent by lazy { ComposerContentController(this) }
    val data = MutableStateFlow<Map<String, JSONObject>>(emptyMap())
    val loading = MutableStateFlow<Set<String>>(emptySet())
    /** Successful IDs remain visible during partial failures so UI can keep only unfinished selections. */
    val bulkCompletedThreadIds = MutableStateFlow<Set<String>>(emptySet())
    val bulkActionSummary = MutableStateFlow("")
    val errors = MutableStateFlow<Map<String, String>>(emptyMap())
    /** One explicit receipt for each user-triggered RPC; page reads stay silent. */
    val actionFeedback = MutableSharedFlow<ActionFeedbackReceipt>(extraBufferCapacity = 24)
    internal val threadOrganization by lazy { ThreadOrganizationController(this) }
    val latestDiff = ConversationField(conversationState, { it.diff }) { s, v -> s.copy(diff = v) }
    val modelId = MutableStateFlow(composerDrafts.value[composerOwner(null)]?.options?.model.orEmpty())
    val selectedMode = MutableStateFlow(composerDrafts.value[composerOwner(null)]?.options?.mode ?: "default")
    internal val pendingComposerImages = MutableStateFlow<Set<String>>(emptySet())
    val accountLoginUrl = MutableStateFlow("")
    val deviceLoginCode = MutableStateFlow("")
    val deviceLoginUrl = MutableStateFlow("")
    val activeLoginId = MutableStateFlow("")
    val loginBusy = MutableStateFlow(false)
    val steerBusy = MutableStateFlow(false)
    internal val queuePausedThreads = MutableStateFlow<Set<String>>(emptySet())
    val terminalSession = MutableStateFlow(TerminalSessionState())
    internal val terminalTranscript = TerminalTranscriptStore(File(application.filesDir, "terminal-transcript"))
    private val workspaceTasks by lazy {
        WorkspaceTaskOperations(this, workspaceRoot, workspacePreferences, viewModelScope)
    }
    private val terminalTasks by lazy { TerminalTaskOperations(this, viewModelScope) }
    internal val catalogWarmup by lazy { CatalogWarmupController(this, viewModelScope, latestRequests) }
    internal val mcpCatalog by lazy { McpCatalogController(this) }
    internal val accountMetrics by lazy {
        AccountMetricsSync(viewModelScope, data, loading, errors,
            canRead = { bridge.status.value == EngineStatus.Connected && !AppDataTransfer.state.value.busy },
            clock = android.os.SystemClock::elapsedRealtime,
            read = { key ->
                when (key) {
                    "usage" -> bridge.request("account/usage/read", null)
                    "limits" -> bridge.request("account/rateLimits/read", null)
                    else -> error("未知账号统计类型：$key")
                }
            })
    }
    private var firstTurnImages: List<String> = emptyList()

    internal val recoveryDelayMs = MutableStateFlow<Long?>(null)
    private val runtimeConnection by lazy { RuntimeConnectionController(this) }
    fun connect() = runtimeConnection.connect()
    fun reconnectIfNeeded() = runtimeConnection.reconnectIfNeeded()

    /**
     * 同一结果键只接受最后发起的请求，避免较早的慢响应覆盖新结果。
     * 每次请求立即撤销旧的成功/错误状态；UI 只显示本轮实际返回的结果。
     */
    private val latestRequests = LatestRequestGate()
    internal val configTransactionBusy = MutableStateFlow(false)
    internal var configGeneration = 0L
        private set
    internal fun invalidateConfigScope() {
        configGeneration += 1
        val keys = setOf("config", "composerPermissionWorkspace", "composerPermissionConfig", "permissionProfiles", "securityRequirements", "hookRequirements")
        keys.forEach { latestRequests.cancel(it) }
        data.value = data.value - keys
        loading.value = loading.value - keys
        errors.value = errors.value - keys
    }
    internal val featureSwitches by lazy { FeatureSwitchController(this) }
    internal val extensions by lazy { ExtensionCatalogController(this, latestRequests) }
    private val ownedRequests by lazy { OwnedRpcRequestController(this, latestRequests) }
    internal fun invalidateAccountReceipts() = ownedRequests.invalidateAccountReceipts()
    private fun request(key: String, method: String, params: JSONObject?,
        onSuccess: ((JSONObject) -> Unit)?, userAction: Boolean = false) =
        ownedRequests.request(key, method, params, onSuccess, userAction)

    fun refresh(
        key: String,
        method: String,
        params: JSONObject? = JSONObject(),
        onSuccess: ((JSONObject) -> Unit)? = null
    ) {
        // A verified config write owns its snapshot until readback completes.
        if (key == "config" && method == "config/read" &&
            configBusy()) return
        request(key, method, params, onSuccess)
    }

    /** Read only live MCP connections; reload the native catalog when the agent edits config. */
    fun refreshMcpServices() = mcpCatalog.refresh()

    fun ensureCatalog(key: String, method: String, params: JSONObject = JSONObject()) =
        catalogWarmup.ensure(key, method, params)

    fun rpc(key: String, method: String, params: JSONObject = JSONObject(), after: (() -> Unit)? = null) {
        request(key, method, params, if (after == null) null else { _ -> after() }, userAction = true)
    }

    internal val conversationActions by lazy { ConversationActionController(this) }
    internal val answerVersions by lazy { AnswerVersionStore(getApplication<Application>().getSharedPreferences("answer-versions", 0)) }
    internal val answerWorkspaces by lazy { AnswerWorkspaceStore(workspaceRoot, File(getApplication<Application>().filesDir, AnswerCheckpointFormat.ROOT)) }
    fun runSessionAction(id: String, fork: Boolean) =
        if (fork) conversationActions.fork(id) else conversationActions.compact(id)

    fun bulkThreadAction(kind: String, ids: List<String>, after: () -> Unit) =
        threadOrganization.execute(kind, ids, after = after)

    /** 一次读取得到的最近会话同时更新首页和侧栏，避免同一事件触发双重网络请求。 */
    internal val sessions by lazy { SessionListController(this, viewModelScope) }
    internal fun syncRecentThreads() = sessions.refresh()

    fun chooseThread(id: String, forceReload: Boolean = false, onOpened: (() -> Unit)? = null) =
        selectConversation(id, forceReload, onOpened, workspaceTasks, workspacePreferences)

    /** Workspace and plan actions share the validated local project root. */
    fun selectWorkspace(folder: File) = workspaceTasks.selectWorkspace(folder)
    fun selectProject(projectId: String, folder: File) =
        workspaceTasks.selectProject(projectId, folder)
    fun approvePlan(markdown: String) = workspaceTasks.approvePlan(markdown)

    fun newThread() { resetConversation(activeWorkspace.value, activeProjectId.value) }
    internal fun openRemoteTask(binding: RemoteExecutionBinding): Boolean {
        if (bridge.status.value != EngineStatus.Connected ||
            !remoteBindings.permitsRegistration(binding.id, binding.endpoint)) return false
        if (!resetConversation(workspaceRoot.absolutePath, null)) return false
        conversationState.update { it.copy(remote = binding) }
        restoreComposerOptions(null)
        return true
    }
    internal fun resetConversation(workspace: String, projectId: String?, agentDraft: Boolean = false): Boolean {
        if (!globalWorkSnapshot().canStartNewTask) {
            errors.value = errors.value + ("conversation" to "本机备份或恢复正在进行，完成后再新建会话")
            return false
        }
        if (threadSwitching.value || loadingEarlier.value || timelineCompacting) {
            errors.value = errors.value + ("conversation" to "正在整理会话记录，请稍后新建任务")
            return false
        }
        if (firstThreadStarting.value) {
            errors.value = errors.value + ("conversation" to "正在建立新会话，完成后即可新建")
            return false
        }
        // Preserve unsent text in case New Chat is tapped accidentally.
        // Users can intentionally clear it in the input field.
        activeThread.value?.let { current ->
            if (running.value) liveTimelines.capture(current, activeTurn.value, timeline.value)
        }
        errors.value = errors.value - "conversation"
        olderServerCursor.value = null
        historyTurnOutcomes = null
        val epoch = ++timelineEpoch
        agentDraftWorkspace.value = if (agentDraft) workspace else null
        conversationState.value = ActiveConversationContext(workspace, projectId)
        restoreComposerOptions(null)
        viewModelScope.launch { timelineWindow.clear(epoch) }
        return true
    }

    internal val agentActionNavigation = MutableStateFlow(0L)

    fun editComposerText(threadId: String?, text: String) =
        attachmentController.editText(composerOwner(threadId), text)

    fun editComposerImages(threadId: String?, paths: List<String>) =
        attachmentController.editImages(composerOwner(threadId), paths)

    fun beginImageSelection(threadId: String?): Boolean =
        attachmentController.beginImageSelection(composerOwner(threadId))

    fun importPickedImages(selected: List<Uri>) =
        attachmentController.importPickedImages(selected)

    fun removeComposerImage(threadId: String?, path: String) =
        attachmentController.removeComposerImage(composerOwner(threadId), path)

    fun reportFullAttachmentSelection(threadId: String?) =
        attachmentController.reportFullAttachmentSelection(composerOwner(threadId))

    fun clearComposerAfterAccepted(threadId: String?, text: String, images: List<String>) =
        attachmentController.clearAfterAccepted(composerOwner(threadId), text, images)

    internal suspend fun createMediaAnalysisSource(draftOwner: String, owner: TaskRequestOwner,
        onCreated: (String) -> Unit) =
        establishMediaAnalysisSource(draftOwner, owner, workspaceRoot, workspaceTasks, onCreated)

    fun sendPrompt(text: String, localImages: List<String> = emptyList(),
                   references: List<ComposerReference> = emptyList(), afterAccepted: (String) -> Unit = {}) {
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
        if (activeThread.value?.let(::answerFamilyBusy) == true) {
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
        // Own the destination synchronously; a second tap on this thread cannot
        // submit twice and switching to another thread does not transfer the turn.
        if (submittedThread == null) {
            firstThreadStarting.value = true
            firstTurnImages = images
        } else {
            threadExecutions.value = ThreadExecutionPolicy.reserve(
                threadExecutions.value, submittedThread, images)
            threadFailures.value = threadFailures.value - submittedThread
            threadActivities.value = ThreadActivityPolicy.updated(
                threadActivities.value, submittedThread, ThreadActivity.Running)
        }
        running.value = true
        val chosenModel = modelId.value
        val chosenMode = selectedMode.value
        val chosenWorkspace = activeWorkspace.value
        val chosenRemote = conversationState.value.remote
        val draftOwner = composerOwner(submittedThread, chosenWorkspace)
        val chosenOptions = composerDrafts.value[draftOwner]?.options ?: ComposerTaskOptions()
        val chosenEffort = chosenOptions.effort
        val chosenProject = activeProjectId.value
        viewModelScope.launch {
            var newlyCreatedThreadId: String? = null
            var turnRequestDispatched = false
            val pendingId = "pending-" + UUID.randomUUID()
            if (activeThread.value == submittedThread) timeline.value = timeline.value + TimelineItem(
                pendingId, "user",
                if (images.isEmpty()) prompt else
                    listOf(prompt, LocalMediaAttachmentPolicy.summary(images)).filter { it.isNotBlank() }.joinToString("\n"),
                "sending")
            try {
                // Android must confirm foreground mode before any turn RPC is sent.
                CodexForegroundService.startAndAwaitReady(getApplication())
                require(chosenMode == "default" || chosenMode == "plan") {
                    "未知协作方式：" + chosenMode
                }
                chosenRemote?.requireTextOnly(images, references)
                check(bridge.connectedSession.value == epoch && permit()) { "连接或账号已变化，草稿已保留" }
                if (chosenRemote != null) {
                    if (submittedThread == null) remoteBindings.ready(epoch, chosenRemote) { method, params ->
                        owner.request(this@CodexViewModel, method, params)
                    } else ensureRemoteThread(submittedThread, chosenRemote, expectedSession = epoch, sendPermit = permit)
                }
                val permissionConfig = owner.request(this@CodexViewModel, "config/read", JSONObject().put("cwd", chosenWorkspace))
                val chosenPermission = AnswerWorkspaceStore.permission(workspaceRoot, chosenWorkspace, ComposerPermissionPolicy.selected(permissionConfig))
                val (provider, requestModel, catalog, effectiveConfig) = ComposerProviderScope.submission(
                    permissionConfig.getJSONObject("config"), submittedThread, chosenModel, chosenMode,
                    chosenOptions, mediaKinds = LocalMediaAttachmentPolicy.modalities(images)) { method, params -> owner.request(this@CodexViewModel, method, params) }
                AppDataTransfer.withLocalMutation { LocalMediaAttachmentPolicy.prepare(images, LocalImageAttachments.directory(getApplication())) { owner.requireCurrent(this@CodexViewModel) } }
                ComposerInputPolicy.validate(images, references, LocalImageAttachments.directory(getApplication()), File(chosenWorkspace))
                owner.requireCurrent(this@CodexViewModel)
                if (submittedThread == null && chosenRemote == null) workspaceTasks.relocateProject(chosenProject, owner)
                if (chosenRemote == null) captureAnswerBaseline(pendingId.removePrefix("pending-"), chosenWorkspace, submittedThread)
                LocalImageAttachments.preserveForHistory(getApplication(), images, pendingId.removePrefix("pending-"))
                val outcome = TurnSubmissionCoordinator { method, params ->
                    check(permit()) { "账号已变化，未继续提交任务" }
                    if (method == "thread/start" && chosenRemote != null) {
                        val response = bridge.request(method, chosenRemote.apply(params), sendPermit = permit, expectedSession = epoch)
                        val created = response.getJSONObject("thread")
                        remoteBindings.bind(created.getString("id"), chosenRemote)
                        check(chosenRemote.matches(created)) { "内核没有确认远端会话，未发送任务" }
                        response
                    } else if (method == "thread/start") modelPreviews.request(method, params, permissionConfig, expectedSession = epoch, sendPermit = permit)
                    else bridge.request(method, if (chosenRemote != null && method == "turn/start") chosenRemote.apply(params) else params, sendPermit = permit, expectedSession = epoch)
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
                        newlyCreatedThreadId = createdId
                        threadExecutions.value = ThreadExecutionPolicy.reserve(
                            threadExecutions.value, createdId, images)
                        threadFailures.value = threadFailures.value - createdId
                        threadActivities.value = ThreadActivityPolicy.updated(
                            threadActivities.value, createdId, ThreadActivity.Running)
                        firstTurnImages = emptyList()
                        firstThreadStarting.value = false
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
                        if (id in threadExecutions.value) {
                            threadExecutions.value = ThreadExecutionPolicy.started(
                                threadExecutions.value, id, outcome.turnId)
                            if (activeThread.value == id) {
                                conversationState.update { it.copy(turnId = threadExecutions.value[id]?.turnId, running = true) }
                                errors.value = errors.value - "conversation"
                            }
                        }
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
                firstThreadStarting.value = false
                firstTurnImages = emptyList()
                val submittedId = newlyCreatedThreadId ?: submittedThread
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
                if (created != null && FirstTurnRecoveryPolicy.explicitlyRejected(failure)) {
                    try {
                        recoveryNote = FirstTurnRecoveryController { method, params, timeout ->
                            owner.request(this@CodexViewModel, method, params, timeout)
                        }.reclaim(created, { activeThread.value }, { activeTurn.value }) {
                            owner.requireCurrent(this@CodexViewModel)
                            composerDrafts.update {
                                ThreadComposerDrafts.reclaimRejectedNewThread(it, created, draftOwner)
                            }
                            restoreComposerOptions(null)
                            activeThread.value = null
                            sessions.refreshChanged(created)
                        }
                    } catch (recoveryFailure: Exception) {
                        recoveryNote = "；新建会话检查失败：" +
                            (recoveryFailure.message ?: "请到侧栏会话列表核查")
                    }
                }
                if (activeThread.value == submittedId ||
                    (submittedThread == null && activeThread.value == null)) {
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
                if (activeThread.value == submittedId) {
                    errors.value = errors.value + ("conversation" to message)
                    timeline.value = timeline.value + TimelineItem(
                        "failure-" + UUID.randomUUID(), "error", message)
                }
            }
        }
    }

    /**
     * Capture the running turn and reserve ownership before launching so rapid
     * repeated taps do not send the same instruction twice.
     */
    fun steerPrompt(text: String, afterAccepted: () -> Unit) =
        submitRunningComposer(ComposerDraft(text = text), "steer", accepted = afterAccepted)

    fun interrupt() {
        val tid = activeThread.value ?: return
        interruptThread(tid)
    }

    fun interruptThread(threadId: String) {
        val turn = threadExecutions.value[threadId]?.turnId ?: return
        rpc(if (threadId == activeThread.value) "interrupt" else "interrupt:" + threadId,
            "turn/interrupt", JSONObject().put("threadId", threadId).put("turnId", turn))
    }

    fun answerApproval(prompt: ServerPrompt, action: ApprovalAction) {
        viewModelScope.launch {
            try {
                val decision = ApprovalDecisionPolicy.resolve(prompt, action)
                val result = ApprovalProtocol.result(prompt, decision)
                bridge.answer(prompt, result)
                approvalAudit.record(prompt, decision)
                errors.value = errors.value - "approvals"
            } catch (failure: Exception) {
                errors.value = errors.value + ("approvals" to (failure.message ?: "授权回复失败"))
            }
        }
    }

    fun answerUserInput(prompt: ServerPrompt, answers: Map<String, String>) {
        viewModelScope.launch {
            try {
                bridge.answer(prompt, ApprovalProtocol.result(prompt, "submit", answers))
            } catch (failure: Exception) {
                errors.value = errors.value + ("approvals" to (failure.message ?: "回答提交失败"))
            }
        }
    }

    fun answerMcpElicitation(prompt: ServerPrompt, answers: Map<String, String>) {
        viewModelScope.launch {
            try {
                val result = ApprovalProtocol.result(prompt, "accept", answers)
                bridge.answer(prompt, result)
                errors.value = errors.value - "approvals"
            } catch (failure: Exception) {
                errors.value = errors.value + ("approvals" to (failure.message ?: "MCP 表单无效"))
            }
        }
    }

    fun startTerminalCommand(command: String, tty: Boolean = false, networkEnabled: Boolean = false) =
        terminalTasks.startTerminalCommand(command, tty, networkEnabled)
    fun resizeTerminal(rows: Int, cols: Int) = terminalTasks.resizeTerminal(rows, cols)
    fun sendTerminalInput(line: String, afterSent: () -> Unit = {}) =
        terminalTasks.sendTerminalInput(line, afterSent)
    fun stopTerminalCommand() = terminalTasks.stopTerminalCommand()

    fun startLogin() = beginLogin("chatgpt")

    fun startDeviceLogin() = beginLogin("chatgptDeviceCode")

    /** Reserve the login synchronously: concurrent taps cannot replace each other's codes. */
    private fun beginLogin(type: String) {
        if (loginBusy.value || activeLoginId.value.isNotBlank() ||
            "accountLogout" in loading.value || "accountSessionSwitch" in loading.value ||
            configTransactionBusy.value || securitySaveBusy.value || connectionRestarter.busy.value ||
            AccountVoiceSettingsLease.blocks("account/login/start", loading.value)) {
            errors.value = errors.value + ("account" to "已有账号、配置或核心切换操作，请完成当前操作后重试")
            return
        }
        val owner = captureTaskOwner() ?: run {
            errors.value = errors.value + ("account" to "运行核心尚未连接")
            return
        }
        loginBusy.value = true
        viewModelScope.launch {
            try {
                val result = owner.request(this@CodexViewModel, "account/login/start", JSONObject().put("type", type))
                if (type == "chatgptDeviceCode") {
                    val login = AccountLoginContract.device(result)
                    activeLoginId.value = login.id
                    accountLoginUrl.value = ""
                    deviceLoginUrl.value = login.url
                    deviceLoginCode.value = login.code
                } else {
                    val login = AccountLoginContract.browser(result)
                    activeLoginId.value = login.id
                    deviceLoginCode.value = ""
                    deviceLoginUrl.value = ""
                    accountLoginUrl.value = login.url
                }
                errors.value = errors.value - "account"
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException && failure !is kotlinx.coroutines.TimeoutCancellationException) throw failure
                if (!owner.current(this@CodexViewModel)) return@launch
                errors.value = errors.value + ("account" to
                    (failure.message ?: "无法发起本次登录"))
            } finally {
                loginBusy.value = false
            }
        }
    }

    fun cancelLogin() {
        val id = activeLoginId.value
        if (id.isBlank() || loginBusy.value) return
        val owner = captureTaskOwner() ?: return
        loginBusy.value = true
        viewModelScope.launch {
            try {
                owner.request(this@CodexViewModel, "account/login/cancel", JSONObject().put("loginId", id))
                if (activeLoginId.value == id) {
                    activeLoginId.value = ""
                    accountLoginUrl.value = ""
                    deviceLoginCode.value = ""
                    deviceLoginUrl.value = ""
                }
                errors.value = errors.value - "account"
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException && failure !is kotlinx.coroutines.TimeoutCancellationException) throw failure
                if (!owner.current(this@CodexViewModel)) return@launch
                errors.value = errors.value + ("account" to
                    (failure.message ?: "无法取消这次登录"))
            } finally {
                loginBusy.value = false
            }
        }
    }

    private suspend fun receive(notification: CoreNotification) {
        if (bridge.isCurrentEvent(notification.wire)) CodexNotificationDispatcher.dispatch(this, notification)
    }

    override fun onCleared() {
        modelPreviews.close()
        bridge.close()
        super.onCleared()
    }

    // All controller delegates and request state must exist before Main.immediate collectors run.
    init {
        restoredWorkspace.warning?.let { warning ->
            errors.value = errors.value + ("workspace" to warning)
        }
        viewModelScope.launch {
            collectTimelineNotifications(bridge.events, ::receive)
        }
        draftStorage.start { key, warning ->
            errors.value = if (warning == null) errors.value - key
                else errors.value + (key to warning)
        }
        viewModelScope.launch {
            bridge.status.collect { current ->
                if (current is EngineStatus.Unavailable) {
                    historyTurnOutcomes = null
                    activeLoginId.value = ""
                    accountLoginUrl.value = ""
                    deviceLoginCode.value = ""
                    deviceLoginUrl.value = ""
                }
                if (current is EngineStatus.Unavailable &&
                    (running.value || threadExecutions.value.isNotEmpty() || firstThreadStarting.value)) {
                    val wasVisible = running.value
                    threadExecutions.value.keys.forEach { id ->
                        threadActivities.value = ThreadActivityPolicy.updated(
                            threadActivities.value, id, ThreadActivity.Failed)
                    }
                    threadExecutions.value = emptyMap()
                    firstThreadStarting.value = false
                    firstTurnImages = emptyList()
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

        viewModelScope.launch {
            AppDataTransfer.state.collect { transfer ->
                if (!transfer.busy) {
                    accountMetrics.refreshPending()
                    sessions.refreshPending()
                }
            }
        }

        extensions.observeWorkspace(viewModelScope)
        connect()
    }
}
