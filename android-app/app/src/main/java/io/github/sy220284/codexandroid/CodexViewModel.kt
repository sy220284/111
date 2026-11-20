package io.github.sy220284.codexandroid

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class TimelineItem(
    val id: String,
    val kind: String,
    val text: String,
    val status: String = ""
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
    val accountLoginUrl = MutableStateFlow("")

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
                                item.optString("status")
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
        activeThread.value = null
        activeTurn.value = null
        timeline.value = emptyList()
        latestDiff.value = ""
        running.value = false
    }

    fun sendPrompt(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            running.value = true
            timeline.value = timeline.value + TimelineItem("user-" + System.nanoTime(), "user", text)
            try {
                var id = activeThread.value
                if (id == null) {
                    val args = JSONObject().put("cwd", workingDirectory)
                    if (modelId.value.isNotBlank()) args.put("model", modelId.value)
                    id = bridge.request("thread/start", args).getJSONObject("thread").getString("id")
                    activeThread.value = id
                    refresh("threads", "thread/list", JSONObject().put("limit", 30))
                }
                val input = JSONArray().put(JSONObject().put("type", "text").put("text", text))
                val response = bridge.request("turn/start", JSONObject().put("threadId", id).put("input", input))
                activeTurn.value = response.optJSONObject("turn")?.optString("id")
            } catch (failure: Exception) {
                running.value = false
                errors.value = errors.value + ("conversation" to (failure.message ?: "发送失败"))
                timeline.value = timeline.value + TimelineItem("failure-" + System.nanoTime(), "error", failure.message ?: "发送失败")
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
                val result = when (prompt.method) {
                    "item/commandExecution/requestApproval", "item/fileChange/requestApproval" ->
                        JSONObject().put("decision", decision)
                    "item/permissions/requestApproval" ->
                        JSONObject().put("permissions", JSONObject()).put("scope", "turn")
                    "mcpServer/elicitation/request" ->
                        JSONObject().put("action", "decline").put("content", JSONObject.NULL)
                    "item/tool/requestUserInput" -> {
                        val questionId = prompt.params.optJSONArray("questions")?.optJSONObject(0)?.optString("id")
                        val answers = JSONObject()
                        if (!questionId.isNullOrBlank()) {
                            answers.put(questionId, JSONObject().put("answers", JSONArray().put(customAnswer)))
                        }
                        JSONObject().put("answers", answers)
                    }
                    else -> throw IllegalArgumentException("此类服务端请求需要专用授权处理：" + prompt.method)
                }
                bridge.answer(prompt, result)
            } catch (failure: Exception) {
                errors.value = errors.value + ("approvals" to (failure.message ?: "授权回复失败"))
            }
        }
    }

    fun startLogin() {
        viewModelScope.launch {
            try {
                val result = bridge.request("account/login/start", JSONObject().put("type", "chatgpt"))
                accountLoginUrl.value = result.optString("authUrl").ifBlank { result.optString("url") }
                refresh("account", "account/read", JSONObject())
            } catch (failure: Exception) {
                errors.value = errors.value + ("account" to (failure.message ?: "登录请求失败"))
            }
        }
    }

    private fun receive(message: JSONObject) {
        val method = message.optString("method")
        val params = message.optJSONObject("params") ?: JSONObject()
        val threadId = params.optString("threadId")
        if (method == "account/login/completed" || method == "account/updated") {
            refresh("account", "account/read", JSONObject())
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
                val entry = TimelineItem(id, kind, description, item.optString("status"))
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
