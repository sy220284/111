package io.github.sy220284.codexandroid

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class CodexViewModel(application: Application) : AndroidViewModel(application) {
    // Owned by CodexApplication for the entire process, shared by Activity and foreground Service.
    val bridge = CodexConnection(application.applicationContext)
    internal val approvalAudit = ApprovalAuditController(application.applicationContext)
    val workingDirectory = application.filesDir.resolve("projects").apply { mkdirs() }.absolutePath
    val activeThread = MutableStateFlow<String?>(null)
    val threadSwitching = MutableStateFlow(false)
    val activeTurn = MutableStateFlow<String?>(null)
    val running = MutableStateFlow(false)
    val timeline = MutableStateFlow<List<TimelineItem>>(emptyList())
    private val timelineWindow = TimelineWindowStore(File(application.cacheDir, "codex-timeline-window.jsonl"))
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
    val composerDrafts = MutableStateFlow(DraftPersistence.load(application.applicationContext))
    private val attachmentController by lazy {
        AttachmentImportController(getApplication(), viewModelScope, composerDrafts, errors)
    }
    val attachmentImport get() = attachmentController.state
    val data = MutableStateFlow<Map<String, JSONObject>>(emptyMap())
    val loading = MutableStateFlow<Set<String>>(emptySet())
    /** Successful IDs remain visible during partial failures so UI can keep only unfinished selections. */
    val bulkCompletedThreadIds = MutableStateFlow<Set<String>>(emptySet())
    val errors = MutableStateFlow<Map<String, String>>(emptyMap())
    /** One explicit receipt for each user-triggered RPC; page reads stay silent. */
    val actionFeedback = MutableSharedFlow<ActionFeedbackReceipt>(extraBufferCapacity = 24)
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
    private var imagePathsForActiveTurn: List<String> = emptyList()

    init {
        viewModelScope.launch {
            bridge.events.collect { receive(it) }
        }
        viewModelScope.launch {
            composerDrafts.collectLatest { snapshot ->
                delay(350)
                try {
                    withContext(Dispatchers.IO) {
                        DraftPersistence.save(application.applicationContext, snapshot)
                    }
                    errors.value = errors.value - "drafts"
                } catch (failure: Exception) {
                    errors.value = errors.value + ("drafts" to
                        (failure.message ?: "未发送文字无法保存到本机"))
                }
            }
        }
        viewModelScope.launch {
            bridge.status.collect { current ->
                if (current is EngineStatus.Unavailable && running.value) {
                    running.value = false
                    activeTurn.value = null
                    timeline.value = timeline.value + TimelineItem(
                        "disconnect-" + UUID.randomUUID(), "error",
                        "运行核心已断开。已保留会话记录和未发送指令，请重新连接后核查任务结果。")
                }
            }
        }
        viewModelScope.launch {
            try {
                LocalImageAttachments.cleanExpired(application.applicationContext)
            } catch (failure: Exception) {
                errors.value = errors.value + ("attachments" to
                    (failure.message ?: "附件缓存清理失败"))
            }
        }
        connect()
    }

    fun connect() = viewModelScope.launch {
        try {
            val previousThread = activeThread.value
            bridge.connect()
            errors.value = errors.value - "engine"
            refresh("account", "account/read", JSONObject())
            refresh("models", "model/list", JSONObject())
            syncRecentThreads()
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
    // Bound transient library-query state. The source of truth remains the local Codex core.
    private val dynamicRequestHistory = ArrayDeque<String>()
    private fun retainRecentQuery(key: String) {
        if (!key.startsWith("library:") && !key.startsWith("fullSearch:")) return
        dynamicRequestHistory.remove(key)
        dynamicRequestHistory.addLast(key)
        while (dynamicRequestHistory.size > 32) {
            val oldest = dynamicRequestHistory.removeFirst()
            latestRequests.cancel(oldest)
            data.value = data.value - oldest
            errors.value = errors.value - oldest
            loading.value = loading.value - oldest
        }
    }

    private fun request(
        key: String,
        method: String,
        params: JSONObject,
        onSuccess: ((JSONObject) -> Unit)?,
        userAction: Boolean = false
    ) {
        retainRecentQuery(key)
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
                    onSuccess?.invoke(response)
                    if (userAction) actionFeedback.emit(
                        ActionFeedbackCopy.success(method, response))
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
        params: JSONObject = JSONObject(),
        onSuccess: ((JSONObject) -> Unit)? = null
    ) = request(key, method, params, onSuccess)

    fun rpc(key: String, method: String, params: JSONObject = JSONObject(), after: (() -> Unit)? = null) {
        request(key, method, params, if (after == null) null else { _ -> after() }, userAction = true)
    }

    /**
     * Batch management is serialized: every RPC must finish before the next begins.
     * A partially failed batch reports exact progress; the user can retry remaining items.
     */
    fun bulkThreadAction(kind: String, ids: List<String>, after: () -> Unit) {
        val method = try {
            ThreadBulkActionPolicy.method(kind)
        } catch (failure: IllegalArgumentException) {
            errors.value = errors.value + ("threadLibraryBulk" to
                (failure.message ?: "未知批量操作"))
            return
        }
        val targets = ThreadBulkActionPolicy.uniqueIds(ids)
        if (targets.isEmpty()) return
        if (running.value || threadSwitching.value || "threadLibraryBulk" in loading.value) {
            errors.value = errors.value + ("threadLibraryBulk" to
                "当前任务仍在执行或已有整理操作，请完成后重试")
            return
        }
        bulkCompletedThreadIds.value = emptySet()
        errors.value = errors.value - "threadLibraryBulk"
        loading.value = loading.value + "threadLibraryBulk"
        viewModelScope.launch {
            var completed = 0
            try {
                for (id in targets) {
                    check(!running.value && !threadSwitching.value) {
                        "批量处理期间启动了新任务，已停止后续操作"
                    }
                    bridge.request(method, JSONObject().put("threadId", id))
                    completed++
                    bulkCompletedThreadIds.value = bulkCompletedThreadIds.value + id
                }
                actionFeedback.emit(ActionFeedbackReceipt(
                    "已${ThreadBulkActionPolicy.display(kind)} $completed 条会话"
                ))
            } catch (failure: Exception) {
                val reason = "已完成 $completed/${targets.size} 条；" +
                    (failure.message ?: "操作中断，请核对未完成项")
                errors.value = errors.value + ("threadLibraryBulk" to reason)
                actionFeedback.emit(ActionFeedbackReceipt(reason, isError = true))
            } finally {
                if (activeThread.value in bulkCompletedThreadIds.value && !running.value) {
                    newThread()
                }
                loading.value = loading.value - "threadLibraryBulk"
                after()
                syncRecentThreads()
            }
        }
    }

    /** 一次读取得到的最近会话同时更新首页和侧栏，避免同一事件触发双重网络请求。 */
    private fun syncRecentThreads() {
        refresh("threads", "thread/list", JSONObject().put("limit", 100)) { response ->
            data.value = data.value + ("sidebarThreads" to response)
        }
    }

    fun chooseThread(id: String) {
        // Serialize resume requests: multiple rapid taps must not race the active session.
        if (threadSwitching.value) return
        if (!ThreadDisplayPolicy.canOpenThread(running.value, activeThread.value, id)) {
            errors.value = errors.value + ("conversation" to
                "当前任务仍在执行，请先结束任务再切换其他会话")
            return
        }
        // Returning to the same running thread must not clear its live timeline.
        if (running.value) return
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
                bridge.request("thread/resume", JSONObject().put("threadId", id))
                // The server history is authoritative; avoid retaining a second full
                // JSON copy after materializing the bounded timeline window.
                val newest = timelineWindow.replace(id, restored)
                activeThread.value = id
                running.value = false
                latestDiff.value = ""
                timeline.value = newest
            } catch (failure: Exception) {
                errors.value = errors.value + ("conversation" to (failure.message ?: "无法打开会话"))
            } finally {
                threadSwitching.value = false
            }
        }
    }

    fun newThread() {
        if (threadSwitching.value) {
            errors.value = errors.value + ("conversation" to "正在恢复会话，请在完成后再新建任务")
            return
        }
        if (running.value) {
            errors.value = errors.value + ("conversation" to "请先中断或完成当前任务，再新建会话")
            return
        }
        // Preserve unsent text in case New Chat is tapped accidentally.
        // Users can intentionally clear it in the input field.
        activeThread.value = null
        activeTurn.value = null
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

    fun sendPrompt(text: String, localImages: List<String> = emptyList(), afterAccepted: () -> Unit = {}) {
        val prompt = text.trim()
        if (threadSwitching.value) {
            errors.value = errors.value + ("conversation" to "正在恢复会话，请在完成后发送指令")
            return
        }
        if ((prompt.isEmpty() && localImages.isEmpty()) || running.value) return
        val images = localImages.toList()
        // Acquire turn ownership before dispatching the coroutine: rapid taps
        // cannot submit duplicate messages even while thread/start is pending.
        running.value = true
        try {
            CodexForegroundService.start(getApplication())
        } catch (failure: Exception) {
            running.value = false
            errors.value = errors.value + ("conversation" to
                ("无法启动后台任务保护：" + (failure.message ?: "系统拒绝启动前台服务")))
            return
        }
        val chosenModel = modelId.value
        val chosenMode = selectedMode.value
        viewModelScope.launch {
            var newlyCreatedThreadId: String? = null
            val pendingId = "pending-" + UUID.randomUUID()
            timeline.value = timeline.value + TimelineItem(
                pendingId, "user",
                if (images.isEmpty()) prompt else
                    listOf(prompt, "图片附件：${images.size} 张").filter { it.isNotBlank() }.joinToString("\n"),
                "sending")
            try {
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
                var id = activeThread.value
                if (id == null) {
                    val args = JSONObject().put("cwd", workingDirectory)
                    if (chosenModel.isNotBlank()) args.put("model", chosenModel)
                    val createdId = bridge.request("thread/start", args)
                        .getJSONObject("thread").getString("id")
                    newlyCreatedThreadId = createdId
                    composerDrafts.update {
                        ThreadComposerDrafts.attachNewThread(it, createdId)
                    }
                    id = createdId
                    activeThread.value = createdId
                    syncRecentThreads()
                }
                val params = TurnRequestContract.build(
                    threadId = id, text = prompt, modelId = chosenModel,
                    mode = chosenMode, catalog = catalog, localImages = images
                )
                val response = bridge.request("turn/start", params)
                activeTurn.value = response.optJSONObject("turn")?.optString("id")
                imagePathsForActiveTurn = images
                errors.value = errors.value - "conversation"
                afterAccepted()
            } catch (failure: Exception) {
                var recoveryNote = ""
                val created = newlyCreatedThreadId
                // Reclaim an empty newly created session only after an explicit
                // server rejection. Never delete after timeout/disconnect:
                // the server may already have accepted the turn.
                if (created != null && FirstTurnRecoveryPolicy.explicitlyRejected(failure)) {
                    try {
                        val checked = bridge.request("thread/read", JSONObject()
                            .put("threadId", created).put("includeTurns", true),
                            timeoutMillis = 20_000L)
                        val turns = checked.optJSONObject("thread")?.optJSONArray("turns")
                        if (turns != null && turns.length() == 0 &&
                            activeThread.value == created && activeTurn.value.isNullOrBlank()) {
                            bridge.request("thread/delete", JSONObject().put("threadId", created))
                            composerDrafts.update {
                                ThreadComposerDrafts.reclaimRejectedNewThread(it, created)
                            }
                            activeThread.value = null
                            syncRecentThreads()
                            recoveryNote = "；新建空会话已清理"
                        }
                    } catch (recoveryFailure: Exception) {
                        recoveryNote = "；新建会话检查失败：" +
                            (recoveryFailure.message ?: "请到会话资料库核查")
                    }
                }
                running.value = false
                timeline.value = timeline.value.filterNot { it.id == pendingId }
                val message = (failure.message ?: "发送失败") +
                    "；原指令已保留，可重试" + recoveryNote
                errors.value = errors.value + ("conversation" to message)
                timeline.value = timeline.value + TimelineItem(
                    "failure-" + UUID.randomUUID(), "error", message)
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
        val turn = activeTurn.value ?: return
        rpc("interrupt", "turn/interrupt", JSONObject().put("threadId", tid).put("turnId", turn))
    }

    fun answerApproval(prompt: ServerPrompt, decision: String, customAnswer: String = "") {
        viewModelScope.launch {
            try {
                val result = ApprovalProtocol.result(prompt, decision,
                    if (customAnswer.isBlank()) emptyMap() else {
                        val key = prompt.params.optJSONArray("questions")?.optJSONObject(0)?.optString("id") ?: ""
                        mapOf(key to customAnswer)
                    })
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

    /** Terminal commands are always launched from an explicitly confirmed UI action. */
    fun startTerminalCommand(command: String) {
        if (terminalSession.value.running) {
            errors.value = errors.value + ("terminal" to "已有命令正在执行，请先停止或结束该命令")
            return
        }
        val id = UUID.randomUUID().toString()
        val args = TerminalSessionContract.execute(command, workingDirectory, id)
        terminalSession.value = TerminalSessionState.start(id, command)
        errors.value = errors.value - "terminal"
        viewModelScope.launch {
            try {
                terminalTranscript.begin(id)
                val result = bridge.request("command/exec", args, timeoutMillis = null)
                terminalTranscript.appendTail(id, result)
                val active = terminalSession.value
                if (active.processId == id) terminalSession.value = active.finish(result)
            } catch (failure: Exception) {
                val active = terminalSession.value
                if (active.processId == id) {
                    terminalSession.value = active.fail(failure.message ?: "终端命令执行失败")
                }
            }
        }
    }

    fun sendTerminalInput(line: String, afterSent: () -> Unit = {}) {
        val state = terminalSession.value
        if (!state.running || state.terminating) return
        val args = TerminalSessionContract.write(state.processId, line)
        viewModelScope.launch {
            try {
                bridge.request("command/exec/write", args)
                errors.value = errors.value - "terminalInput"
                afterSent()
            } catch (failure: Exception) {
                errors.value = errors.value + ("terminalInput" to
                    (failure.message ?: "终端输入失败"))
            }
        }
    }

    fun stopTerminalCommand() {
        val active = terminalSession.value
        if (!active.running || active.terminating) return
        terminalSession.value = active.copy(terminating = true)
        viewModelScope.launch {
            try {
                bridge.request("command/exec/terminate",
                    TerminalSessionContract.terminate(active.processId))
                errors.value = errors.value - "terminalStop"
            } catch (failure: Exception) {
                val current = terminalSession.value
                if (current.processId == active.processId && current.running) {
                    terminalSession.value = current.copy(terminating = false)
                    errors.value = errors.value + ("terminalStop" to
                        (failure.message ?: "无法停止终端命令"))
                }
            }
        }
    }

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
        val method = message.optString("method")
        val params = message.optJSONObject("params") ?: JSONObject()
        val threadId = params.optString("threadId")
        if (method == "account/login/completed") {
            val result = AccountLoginContract.completion(params, activeLoginId.value)
            if (result != null) {
                activeLoginId.value = ""
                accountLoginUrl.value = ""
                deviceLoginCode.value = ""
                deviceLoginUrl.value = ""
                if (result.success) errors.value = errors.value - "account"
                else errors.value = errors.value + ("account" to
                    result.error.ifBlank { "登录未成功，请重新开始授权" })
                refresh("account", "account/read")
            }
        } else if (method == "account/updated") {
            refresh("account", "account/read")
        }
        if (method == "command/exec/outputDelta") {
            val active = terminalSession.value
            if (active.processId.isNotEmpty() &&
                params.optString("processId") == active.processId && active.running) {
                try {
                    terminalSession.value = active.append(params)
                    terminalTranscript.appendNotification(params)
                } catch (failure: Exception) {
                    terminalSession.value = active.copy(error =
                        "终端输出解码失败：" + (failure.message ?: "非法输出"))
                }
            }
            return
        }
        if (method == "thread/started" || method == "thread/archived") {
            syncRecentThreads()
        }
        if (threadId.isNotBlank() && threadId != activeThread.value) return
        when (method) {
            "item/agentMessage/delta", "item/plan/delta",
            "item/reasoning/summaryTextDelta", "item/started", "item/completed" ->
                timeline.value = TimelineEventReducer.reduce(method, params, timeline.value)
            "turn/diff/updated" -> latestDiff.value = params.optString("diff")
            "turn/started" -> {
                activeTurn.value = params.optJSONObject("turn")?.optString("id")
                running.value = true
            }
            "turn/completed" -> {
                running.value = false
                activeTurn.value = null
                timeline.value = timeline.value.map { item ->
                    if (item.id.startsWith("pending-") && item.status == "sending")
                        item.copy(status = "") else item
                }
                syncRecentThreads()
                val finishedImages = imagePathsForActiveTurn
                imagePathsForActiveTurn = emptyList()
                if (finishedImages.isNotEmpty()) viewModelScope.launch {
                    for (path in finishedImages) {
                        try {
                            LocalImageAttachments.release(getApplication(), path)
                        } catch (failure: Exception) {
                            errors.value = errors.value + ("attachments" to
                                (failure.message ?: "无法清理已完成任务的图片"))
                        }
                    }
                }
                val turn = params.optJSONObject("turn")
                if (turn?.optString("status") != "completed") {
                    timeline.value = timeline.value + TimelineItem(
                        "status-" + System.nanoTime(), "error",
                        turn?.optJSONObject("error")?.optString("message") ?: "任务已中断或失败"
                    )
                }
            }
        }
        if (timeline.value.size > TimelineWindowStore.COMPACT_AT) {
            try {
                timeline.value = timelineWindow.compact(activeThread.value, timeline.value)
            } catch (failure: Exception) {
                errors.value = errors.value + ("timeline" to
                    (failure.message ?: "会话历史无法暂存，当前记录仍保留在内存"))
            }
        }
    }

    override fun onCleared() {
        bridge.close()
        super.onCleared()
    }
}
