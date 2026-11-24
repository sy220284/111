package io.github.sy220284.codexandroid

import android.app.Application
import android.net.Uri
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
    internal val approvalAudit = ApprovalAuditController(application.applicationContext)
    private val workspaceRoot = application.filesDir.resolve("projects").apply { mkdirs() }.canonicalFile
    val workingDirectory = workspaceRoot.absolutePath
    private val workspacePreferences = application.getSharedPreferences("codex-workspace", 0)
    private val restoredWorkspace = WorkspaceSelectionContract.restore(
        workspaceRoot, workspacePreferences.getString("activeDirectory", null))
    val activeWorkspace = MutableStateFlow(restoredWorkspace.directory.absolutePath)
    val activeProjectId = MutableStateFlow<String?>(null)
    val projectCatalogRevision = MutableStateFlow(0)
    val planApprovalBusy = MutableStateFlow(false)
    val activeThread = MutableStateFlow<String?>(null)
    internal val threadActivities = MutableStateFlow<Map<String, ThreadActivity>>(emptyMap())
    val threadSwitching = MutableStateFlow(false)
    /** True across the entire permissions save + effective-config readback. */
    val securitySaveBusy = MutableStateFlow(false)
    /** Reserved before asynchronous foreground-service startup for terminal commands. */
    val terminalStarting = MutableStateFlow(false)
    val activeTurn = MutableStateFlow<String?>(null)
    val running = MutableStateFlow(false)
    /** All running threads, including tasks no longer visible on the current page. */
    internal val threadExecutions = MutableStateFlow<Map<String, ThreadExecution>>(emptyMap())
    val threadFailures = MutableStateFlow<Map<String, String>>(emptyMap())
    /** The first RPC must create a real thread ID before we can switch away. */
    val firstThreadStarting = MutableStateFlow(false)
    val timeline = MutableStateFlow<List<TimelineItem>>(emptyList())
    internal val timelineWindow = TimelineWindowStore(File(application.cacheDir, "codex-timeline-window.jsonl"))
    val olderTimelineCount get() = timelineWindow.olderCount
    fun loadEarlierTimeline() {
        val id = activeThread.value ?: return
        viewModelScope.launch {
            try {
                val older = timelineWindow.readEarlier(id)
                if (activeThread.value == id && older.isNotEmpty())
                    timeline.value = older + timeline.value
            } catch (failure: Exception) {
                errors.value = errors.value + ("timeline" to
                    (failure.message ?: "无法加载更早的记录"))
            }
        }
    }
    private val draftStorage = DraftStorageController(application.applicationContext, viewModelScope)
    val composerDrafts = draftStorage.drafts
    // Transient form drafts belong to the session, not a ModalBottomSheet's window.
    internal val taskPanelDrafts = MutableStateFlow<Map<String, TaskPanelDraft>>(emptyMap())
    internal fun editTaskPanelDraft(threadId: String?, edit: (TaskPanelDraft) -> TaskPanelDraft) {
        taskPanelDrafts.update { TaskPanelDraftPolicy.change(it, threadId, edit) }
    }
    private val attachmentController by lazy {
        AttachmentImportController(getApplication(), viewModelScope, composerDrafts, errors) {
            threadExecutions.value.values.flatMap { it.images } + firstTurnImages
        }
    }
    val attachmentImport get() = attachmentController.state
    val data = MutableStateFlow<Map<String, JSONObject>>(emptyMap())
    val loading = MutableStateFlow<Set<String>>(emptySet())
    /** Successful IDs remain visible during partial failures so UI can keep only unfinished selections. */
    val bulkCompletedThreadIds = MutableStateFlow<Set<String>>(emptySet())
    val bulkActionSummary = MutableStateFlow("")
    val errors = MutableStateFlow<Map<String, String>>(emptyMap())
    /** One explicit receipt for each user-triggered RPC; page reads stay silent. */
    val actionFeedback = MutableSharedFlow<ActionFeedbackReceipt>(extraBufferCapacity = 24)
    internal val threadOrganization by lazy { ThreadOrganizationController(this) }
    val latestDiff = MutableStateFlow("")
    val modelId = MutableStateFlow("")
    val selectedMode = MutableStateFlow("default")
    val accountLoginUrl = MutableStateFlow("")
    val deviceLoginCode = MutableStateFlow("")
    val deviceLoginUrl = MutableStateFlow("")
    val activeLoginId = MutableStateFlow("")
    val loginBusy = MutableStateFlow(false)
    val steerBusy = MutableStateFlow(false)
    val terminalSession = MutableStateFlow(TerminalSessionState())
    internal val terminalTranscript = TerminalTranscriptStore(File(application.filesDir, "terminal-transcript"))
    private val workspaceTasks by lazy {
        WorkspaceTaskOperations(this, workspaceRoot, workspacePreferences, viewModelScope)
    }
    private val terminalTasks by lazy { TerminalTaskOperations(this, viewModelScope) }
    internal val catalogWarmup by lazy { CatalogWarmupController(this, viewModelScope) }
    private val mcpCatalog by lazy { McpCatalogController(this) }
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

    init {
        restoredWorkspace.warning?.let { warning ->
            errors.value = errors.value + ("workspace" to warning)
        }
        viewModelScope.launch {
            bridge.events.collect { receive(it) }
        }
        draftStorage.start { key, warning ->
            errors.value = if (warning == null) errors.value - key
                else errors.value + (key to warning)
        }
        viewModelScope.launch {
            bridge.status.collect { current ->
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
                    running.value = false
                    activeTurn.value = null
                    if (wasVisible) timeline.value = timeline.value + TimelineItem(
                        "disconnect-" + UUID.randomUUID(), "error",
                        "运行核心已断开。已保留会话记录和未发送指令，请重新连接后核查任务结果。")
                }
            }
        }

        viewModelScope.launch {
            AppDataTransfer.state.collect { transfer ->
                if (!transfer.busy) accountMetrics.refreshPending()
            }
        }

        connect()
    }

    fun connect() = viewModelScope.launch {
        try {
            val previousThread = activeThread.value
            val alreadyConnected = bridge.status.value == EngineStatus.Connected
            bridge.connect()
            threadOrganization.invalidate()
            mcpCatalog.reset()
            // A new native process invalidates ephemeral status from the old session.
            threadActivities.value = emptyMap()
            errors.value = errors.value - "engine" - "protocol"
            refresh("account", "account/read", JSONObject())
            refresh("models", "model/list", JSONObject())
            syncRecentThreads()
            catalogWarmup.connected()
            if (alreadyConnected) accountMetrics.refreshIfStale() else accountMetrics.accountChanged()
            // Rebuild visible history after a connection loss, without resending a task.
            if (!previousThread.isNullOrBlank() && !running.value) chooseThread(previousThread)
        } catch (failure: Exception) {
            errors.value = errors.value + ("engine" to (failure.message ?: "无法连接运行核心"))
        }
    }

    fun reconnectIfNeeded() {
        if (bridge.status.value is EngineStatus.Unavailable) connect()
    }

    /**
     * 同一结果键只接受最后发起的请求，避免较早的慢响应覆盖新结果。
     * 每次请求立即撤销旧的成功/错误状态；UI 只显示本轮实际返回的结果。
     */
    private val latestRequests = LatestRequestGate()
    // Query cache ownership is independent of the ViewModel's RPC orchestration.
    private val dynamicRequestHistory by lazy {
        TransientRequestCache(latestRequests, data, errors, loading)
    }

    private fun request(
        key: String,
        method: String,
        params: JSONObject?,
        onSuccess: ((JSONObject) -> Unit)?,
        userAction: Boolean = false
    ) {
        if (userAction && DataTransferMutationGate.refuse(this, key, method)) return
        if (method in setOf("thread/delete", "thread/archive") &&
            params?.optString("threadId")?.let { it in threadExecutions.value } == true) {
            val reason = "所选会话仍有执行中的任务，请先停止或等待完成后再整理"
            errors.value = errors.value + (key to reason)
            if (userAction) viewModelScope.launch {
                actionFeedback.emit(ActionFeedbackCopy.failure(method, reason))
            }
            return
        }
        if (userAction && ThreadOrganizationPolicy.isOrganization(method) &&
            ("threadLibraryBulk" in loading.value || "threadLibraryAction" in loading.value)) {
            val reason = "已有会话整理操作，请完成后重试"
            errors.value = errors.value + (key to reason)
            viewModelScope.launch { actionFeedback.emit(ActionFeedbackCopy.failure(method, reason)) }
            return
        }
        val organizationTicket = if (userAction) threadOrganization.begin(method,
            listOfNotNull(params?.optString("threadId")?.takeIf(String::isNotBlank))) else null
        dynamicRequestHistory.retain(key)
        val ticket = latestRequests.begin(key)
        data.value = data.value - key
        errors.value = errors.value - key
        loading.value = loading.value + key
        viewModelScope.launch {
            try {
                val response = bridge.request(method, params)
                if (latestRequests.isLatest(key, ticket)) {
                    data.value = data.value + (key to response)
                    errors.value = errors.value - key
                    if (userAction && method in setOf("account/logout", "accountSession/switch",
                            "modelProvider/default/set", "modelProvider/config/write")) {
                        accountMetrics.accountChanged()
                    }
                    onSuccess?.invoke(response)
                    if (SessionRefreshPolicy.affectsThreads(method)) syncRecentThreads()
                    if (userAction) actionFeedback.emit(
                        ActionFeedbackCopy.success(method, response).copy(
                            undo = threadOrganization.complete(organizationTicket,
                                listOfNotNull(params?.optString("threadId")?.takeIf(String::isNotBlank)))))
                }
            } catch (failure: Exception) {
                if (latestRequests.isLatest(key, ticket)) {
                    val reason = failure.message ?: "请求失败"
                    errors.value = errors.value + (key to reason)
                    if (userAction) actionFeedback.emit(
                        ActionFeedbackCopy.failure(method, reason))
                }
            } finally {
                if (latestRequests.complete(key, ticket)) {
                    loading.value = loading.value - key
                }
            }
        }
    }

    fun refresh(
        key: String,
        method: String,
        params: JSONObject? = JSONObject(),
        onSuccess: ((JSONObject) -> Unit)? = null
    ) = request(key, method, params, onSuccess)

    /** Read only live MCP connections; reload the native catalog when the agent edits config. */
    fun refreshMcpServices() = mcpCatalog.refresh()

    fun ensureCatalog(key: String, method: String, params: JSONObject = JSONObject()) =
        catalogWarmup.ensure(key, method, params)

    fun rpc(key: String, method: String, params: JSONObject = JSONObject(), after: (() -> Unit)? = null) {
        request(key, method, params, if (after == null) null else { _ -> after() }, userAction = true)
    }

    fun bulkThreadAction(kind: String, ids: List<String>, after: () -> Unit) =
        threadOrganization.execute(kind, ids, after = after)

    /** 一次读取得到的最近会话同时更新首页和侧栏，避免同一事件触发双重网络请求。 */
    internal fun syncRecentThreads() {
        refresh("threads", "thread/list", JSONObject().put("limit", 100)) { response ->
            data.value = data.value + ("sidebarThreads" to response)
        }
    }

    fun chooseThread(id: String, onOpened: (() -> Unit)? = null) {
        if (!globalWorkSnapshot().canStartNewTask) {
            errors.value = errors.value + ("conversation" to "本机备份或恢复正在进行，完成后再切换会话")
            return
        }
        // Serialize resume requests: multiple rapid taps must not race the active session.
        if (threadSwitching.value) return
        if (firstThreadStarting.value) {
            errors.value = errors.value + ("conversation" to "正在建立新会话，请在会话创建后切换")
            return
        }
        if (id.isNotBlank() && id == activeThread.value) {
            onOpened?.invoke()
            return
        }
        if (!ThreadDisplayPolicy.canOpenThread(activeThread.value, id, running.value)) return
        threadSwitching.value = true
        errors.value = errors.value - "conversation"
        viewModelScope.launch {
            try {
                // Read and reconstruct the destination before changing the active core session.
                val history = bridge.request("thread/read", JSONObject()
                    .put("threadId", id).put("includeTurns", true))
                val turns = history.optJSONObject("thread")?.optJSONArray("turns") ?: JSONArray()
                val restored = mutableListOf<TimelineItem>()
                for (turnIndex in 0 until turns.length()) {
                    val turn = turns.optJSONObject(turnIndex) ?: continue
                    val items = turn.optJSONArray("items") ?: continue
                    for (itemIndex in 0 until items.length()) {
                        val item = items.optJSONObject(itemIndex) ?: continue
                        val kind = item.optString("type", "event")
                        val text = if (kind == "userMessage") {
                            val parts = item.optJSONArray("content") ?: JSONArray()
                            (0 until parts.length()).mapNotNull { parts.optJSONObject(it)?.optString("text") }
                                .filter { it.isNotBlank() }.joinToString("\n")
                        } else item.optString("text").ifBlank {
                            item.optString("command").ifBlank { item.optString("tool") }
                        }
                        if (text.isNotBlank()) {
                            restored += TimelineItem(
                                item.optString("id", "history-" + turnIndex + "-" + itemIndex),
                                if (kind == "userMessage") "user" else kind,
                                text,
                                item.optString("status"),
                                if (kind == "userMessage") "" else item.toString(2)
                            )
                        }
                    }
                }
                val threadDirectory = WorkspaceSelectionContract.validate(
                    workspaceRoot, File(history.getJSONObject("thread").getString("cwd")))
                bridge.request("thread/resume", JSONObject().put("threadId", id))
                activeWorkspace.value = threadDirectory.absolutePath
                activeProjectId.value = history.getJSONObject("thread")
                    .optString("projectId").takeIf { it.isNotBlank() }
                workspacePreferences.edit()
                    .putString("activeDirectory", threadDirectory.absolutePath).apply()
                // The server history is authoritative; avoid retaining a second full
                // JSON copy after materializing the bounded timeline window.
                val newest = timelineWindow.replace(id, restored)
                activeThread.value = id
                val execution = threadExecutions.value[id]
                running.value = execution != null
                activeTurn.value = execution?.turnId
                errors.value = errors.value - "conversation"
                threadFailures.value[id]?.let { reason ->
                    errors.value = errors.value + ("conversation" to reason)
                }
                latestDiff.value = ThreadDiffHistory.latest(turns)
                timeline.value = newest
                onOpened?.invoke()
            } catch (failure: Exception) {
                errors.value = errors.value + ("conversation" to (failure.message ?: "无法打开会话"))
            } finally {
                threadSwitching.value = false
            }
        }
    }

    /** Workspace and plan actions share the validated local project root. */
    fun selectWorkspace(folder: File) = workspaceTasks.selectWorkspace(folder)
    fun selectProject(projectId: String, folder: File) =
        workspaceTasks.selectProject(projectId, folder)
    fun approvePlan(markdown: String) = workspaceTasks.approvePlan(markdown)

    fun newThread() {
        if (!globalWorkSnapshot().canStartNewTask) {
            errors.value = errors.value + ("conversation" to "本机备份或恢复正在进行，完成后再新建会话")
            return
        }
        if (threadSwitching.value) {
            errors.value = errors.value + ("conversation" to "正在恢复会话，请在完成后再新建任务")
            return
        }
        if (firstThreadStarting.value) {
            errors.value = errors.value + ("conversation" to "正在建立新会话，完成后即可新建")
            return
        }
        // Preserve unsent text in case New Chat is tapped accidentally.
        // Users can intentionally clear it in the input field.
        activeThread.value = null
        activeTurn.value = null
        errors.value = errors.value - "conversation"
        timeline.value = emptyList()
        viewModelScope.launch { timelineWindow.clear() }
        latestDiff.value = ""
        running.value = false
    }

    fun editComposerText(threadId: String?, text: String) =
        attachmentController.editText(threadId, text)

    fun editComposerImages(threadId: String?, paths: List<String>) =
        attachmentController.editImages(threadId, paths)

    fun beginImageSelection(threadId: String?): Boolean =
        attachmentController.beginImageSelection(threadId)

    fun importPickedImages(selected: List<Uri>) =
        attachmentController.importPickedImages(selected)

    fun removeComposerImage(threadId: String?, path: String) =
        attachmentController.removeComposerImage(threadId, path)

    fun reportFullAttachmentSelection(threadId: String?) =
        attachmentController.reportFullAttachmentSelection(threadId)

    fun clearComposerAfterAccepted(threadId: String?, text: String, images: List<String>) =
        attachmentController.clearAfterAccepted(threadId, text, images)

    fun sendPrompt(text: String, localImages: List<String> = emptyList(), afterAccepted: (String) -> Unit = {}) {
        if (!globalWorkSnapshot().canStartNewTask) {
            errors.value = errors.value + ("conversation" to
                "本机数据正在备份或恢复，完成后再发送指令")
            return
        }
        if (connectionRestarter.busy.value) { errors.value = errors.value + ("conversation" to "正在重新连接内核，请完成后发送"); return }
        val prompt = text.trim()
        if (threadSwitching.value) {
            errors.value = errors.value + ("conversation" to "正在恢复会话，请在完成后发送指令")
            return
        }
        val submittedThread = activeThread.value
        if ((prompt.isEmpty() && localImages.isEmpty()) || running.value ||
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
        val chosenProject = activeProjectId.value
        viewModelScope.launch {
            var newlyCreatedThreadId: String? = null
            var turnRequestDispatched = false
            val pendingId = "pending-" + UUID.randomUUID()
            if (activeThread.value == submittedThread) timeline.value = timeline.value + TimelineItem(
                pendingId, "user",
                if (images.isEmpty()) prompt else
                    listOf(prompt, "图片附件：${images.size} 张").filter { it.isNotBlank() }.joinToString("\n"),
                "sending")
            try {
                // Android must confirm foreground mode before any turn RPC is sent.
                CodexForegroundService.startAndAwaitReady(getApplication())
                require(chosenMode == "default" || chosenMode == "plan") {
                    "未知协作方式：" + chosenMode
                }
                require(images.size <= LocalImageAttachments.MAX_FILES) { "图片附件数量超限" }
                val permittedFolder = LocalImageAttachments.directory(getApplication()).canonicalFile
                images.forEach { path ->
                    val file = File(path).canonicalFile
                    require(file.parentFile == permittedFolder && file.isFile && file.length() > 0L) {
                        "图片附件不在本应用缓存中或已失效"
                    }
                }
                val catalog = records(data.value["models"])
                if (chosenMode == "plan") {
                    require(chosenModel.isNotBlank() || catalog.any {
                        it.optString("id").isNotBlank()
                    }) { "计划模式需要先读取可用模型目录" }
                }
                val outcome = TurnSubmissionCoordinator { method, params ->
                    bridge.request(method, params)
                }.submit(
                    existingThreadId = submittedThread,
                    createThreadParams = {
                        val cwd = WorkspaceSelectionContract.validate(
                            workspaceRoot, File(chosenWorkspace)).absolutePath
                        ProjectRequestContract.threadStart(
                            File(cwd), chosenProject, chosenModel)
                    },
                    turnParams = { id ->
                        TurnRequestContract.build(
                            threadId = id, text = prompt, modelId = chosenModel,
                            mode = chosenMode, catalog = catalog, localImages = images
                        )
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
                            ThreadComposerDrafts.attachNewThread(it, createdId)
                        }
                        activeThread.value = createdId
                        syncRecentThreads()
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
                                activeTurn.value = threadExecutions.value[id]?.turnId
                                running.value = true
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
                            bridge.request(method, params, timeoutMillis = timeout)
                        }.reclaim(created, { activeThread.value }, { activeTurn.value }) {
                            composerDrafts.update {
                                ThreadComposerDrafts.reclaimRejectedNewThread(it, created)
                            }
                            activeThread.value = null
                            syncRecentThreads()
                        }
                    } catch (recoveryFailure: Exception) {
                        recoveryNote = "；新建会话检查失败：" +
                            (recoveryFailure.message ?: "请到会话资料库核查")
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
    fun steerPrompt(text: String, afterAccepted: () -> Unit) {
        if (steerBusy.value) return
        val threadId = activeThread.value
        val turnId = activeTurn.value
        if (!running.value || threadId.isNullOrBlank() || turnId.isNullOrBlank()) {
            errors.value = errors.value + ("steer" to "当前没有可补充指令的运行任务")
            return
        }
        val prompt = text.trim()
        if (prompt.isBlank()) return
        steerBusy.value = true
        viewModelScope.launch {
            try {
                bridge.request("turn/steer", TurnRequestContract.steer(threadId, turnId, prompt))
                if (activeThread.value == threadId && activeTurn.value == turnId) {
                    timeline.value = timeline.value + TimelineItem(
                        "steer-" + System.nanoTime(), "user", "补充指令：" + prompt)
                    afterAccepted()
                }
                errors.value = errors.value - "steer"
            } catch (failure: Exception) {
                errors.value = errors.value + ("steer" to
                    (failure.message ?: "补充指令未被接受"))
            } finally {
                steerBusy.value = false
            }
        }
    }

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

    fun startTerminalCommand(command: String, tty: Boolean = false) =
        terminalTasks.startTerminalCommand(command, tty)
    fun resizeTerminal(rows: Int, cols: Int) = terminalTasks.resizeTerminal(rows, cols)
    fun sendTerminalInput(line: String, afterSent: () -> Unit = {}) =
        terminalTasks.sendTerminalInput(line, afterSent)
    fun stopTerminalCommand() = terminalTasks.stopTerminalCommand()

    fun startLogin() = beginLogin("chatgpt")

    fun startDeviceLogin() = beginLogin("chatgptDeviceCode")

    /** Reserve the login synchronously: concurrent taps cannot replace each other's codes. */
    private fun beginLogin(type: String) {
        if (loginBusy.value || activeLoginId.value.isNotBlank()) return
        loginBusy.value = true
        viewModelScope.launch {
            try {
                val result = bridge.request("account/login/start", JSONObject().put("type", type))
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
        loginBusy.value = true
        viewModelScope.launch {
            try {
                bridge.request("account/login/cancel", JSONObject().put("loginId", id))
                if (activeLoginId.value == id) {
                    activeLoginId.value = ""
                    accountLoginUrl.value = ""
                    deviceLoginCode.value = ""
                    deviceLoginUrl.value = ""
                }
                errors.value = errors.value - "account"
            } catch (failure: Exception) {
                errors.value = errors.value + ("account" to
                    (failure.message ?: "无法取消这次登录"))
            } finally {
                loginBusy.value = false
            }
        }
    }

    private suspend fun receive(message: JSONObject) {
        CodexNotificationDispatcher.dispatch(this, message)
    }

    override fun onCleared() {
        bridge.close()
        super.onCleared()
    }
}
