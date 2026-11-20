package io.github.sy220284.codexandroid

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class TimelineItem(
    val id: String,
    val kind: String,
    val text: String,
    val status: String = "",
    val evidence: String = ""
)

class CodexViewModel(application: Application) : AndroidViewModel(application) {
    val bridge = CodexConnection(application.applicationContext)
    val workingDirectory = application.filesDir.resolve("projects").apply { mkdirs() }.absolutePath
    val activeThread = MutableStateFlow<String?>(null)
    val activeTurn = MutableStateFlow<String?>(null)
    val running = MutableStateFlow(false)
    val timeline = MutableStateFlow<List<TimelineItem>>(emptyList())
    val data = MutableStateFlow<Map<String, JSONObject>>(emptyMap())
    val loading = MutableStateFlow<Set<String>>(emptySet())
    val errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val latestDiff = MutableStateFlow("")
    val modelId = MutableStateFlow("")
    val selectedMode = MutableStateFlow("default")
    val accountLoginUrl = MutableStateFlow("")
    val deviceLoginCode = MutableStateFlow("")
    val deviceLoginUrl = MutableStateFlow("")
    val activeLoginId = MutableStateFlow("")
    val loginBusy = MutableStateFlow(false)
    val steerBusy = MutableStateFlow(false)
    private var imagePathsForActiveTurn: List<String> = emptyList()

    init {
        viewModelScope.launch {
            bridge.events.collect { receive(it) }
        }
        connect()
    }

    fun connect() = viewModelScope.launch {
        try {
            bridge.connect()
            refresh("account", "account/read", JSONObject())
            refresh("models", "model/list", JSONObject())
            refresh("threads", "thread/list", JSONObject().put("limit", 30))
        } catch (failure: Exception) {
            errors.value = errors.value + ("engine" to (failure.message ?: "无法连接运行核心"))
        }
    }

    fun refresh(key: String, method: String, params: JSONObject = JSONObject()) {
        viewModelScope.launch {
            loading.value = loading.value + key
            try {
                val response = bridge.request(method, params)
                data.value = data.value + (key to response)
                errors.value = errors.value - key
            } catch (failure: Exception) {
                errors.value = errors.value + (key to (failure.message ?: "请求失败"))
            } finally {
                loading.value = loading.value - key
            }
        }
    }

    fun rpc(key: String, method: String, params: JSONObject = JSONObject(), after: (() -> Unit)? = null) {
        viewModelScope.launch {
            loading.value = loading.value + key
            try {
                val response = bridge.request(method, params)
                data.value = data.value + (key to response)
                errors.value = errors.value - key
                after?.invoke()
            } catch (failure: Exception) {
                errors.value = errors.value + (key to (failure.message ?: "请求失败"))
            } finally {
                loading.value = loading.value - key
            }
        }
    }

    fun chooseThread(id: String) {
        viewModelScope.launch {
            try {
                bridge.request("thread/resume", JSONObject().put("threadId", id))
                activeThread.value = id
                running.value = false
                timeline.value = emptyList()
                latestDiff.value = ""
                val history = bridge.request("thread/read", JSONObject()
                    .put("threadId", id).put("includeTurns", true))
                data.value = data.value + ("threadHistory" to history)
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
                timeline.value = restored
            } catch (failure: Exception) {
                errors.value = errors.value + ("conversation" to (failure.message ?: "无法打开会话"))
            }
        }
    }

    fun newThread() {
        if (running.value) {
            errors.value = errors.value + ("conversation" to "请先中断或完成当前任务，再新建会话")
            return
        }
        activeThread.value = null
        activeTurn.value = null
        timeline.value = emptyList()
        latestDiff.value = ""
        running.value = false
    }

    fun sendPrompt(text: String, localImages: List<String> = emptyList(), afterAccepted: () -> Unit = {}) {
        val prompt = text.trim()
        if ((prompt.isEmpty() && localImages.isEmpty()) || running.value) return
        val images = localImages.toList()
        // Acquire turn ownership before dispatching the coroutine: rapid taps
        // cannot submit duplicate messages even while thread/start is pending.
        running.value = true
        val chosenModel = modelId.value
        val chosenMode = selectedMode.value
        viewModelScope.launch {
            timeline.value = timeline.value + TimelineItem(
                "user-" + System.nanoTime(), "user",
                if (images.isEmpty()) prompt else
                    listOf(prompt, "图片附件：${images.size} 张").filter { it.isNotBlank() }.joinToString("\n"))
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
                    id = bridge.request("thread/start", args).getJSONObject("thread").getString("id")
                    activeThread.value = id
                    refresh("threads", "thread/list", JSONObject().put("limit", 30))
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
                running.value = false
                errors.value = errors.value + ("conversation" to (failure.message ?: "发送失败"))
                timeline.value = timeline.value + TimelineItem(
                    "failure-" + System.nanoTime(), "error", failure.message ?: "发送失败")
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

    private fun receive(message: JSONObject) {
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
        if (method == "thread/started" || method == "thread/archived") {
            refresh("threads", "thread/list", JSONObject().put("limit", 30))
        }
        if (threadId.isNotBlank() && threadId != activeThread.value) return
        when (method) {
            "item/agentMessage/delta", "item/plan/delta", "item/reasoning/summaryTextDelta" -> {
                val key = params.optString("itemId", "stream-" + method)
                val delta = params.optString("delta")
                val current = timeline.value.toMutableList()
                val idx = current.indexOfLast { it.id == key }
                if (idx == -1) current += TimelineItem(key, method.substringAfter("item/").substringBefore("/"), delta, "streaming")
                else current[idx] = current[idx].copy(text = current[idx].text + delta)
                timeline.value = current
            }
            "item/started", "item/completed" -> {
                val item = params.optJSONObject("item") ?: return
                val id = item.optString("id", "item-" + System.nanoTime())
                val kind = item.optString("type", "event")
                val description = when (kind) {
                    "commandExecution" -> item.optString("command")
                    "fileChange" -> "文件变更：" + item.optJSONObject("changes")?.length().toString() + " 个文件"
                    "mcpToolCall" -> item.optString("tool")
                    else -> item.optString("text", kind)
                }
                val all = timeline.value.toMutableList()
                val pos = all.indexOfLast { it.id == id }
                val entry = TimelineItem(id, kind, description, item.optString("status"), item.toString(2))
                if (pos < 0) all += entry else all[pos] = entry
                timeline.value = all
            }
            "turn/diff/updated" -> latestDiff.value = params.optString("diff")
            "turn/started" -> {
                activeTurn.value = params.optJSONObject("turn")?.optString("id")
                running.value = true
            }
            "turn/completed" -> {
                running.value = false
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
    }

    override fun onCleared() {
        bridge.close()
        super.onCleared()
    }
}
