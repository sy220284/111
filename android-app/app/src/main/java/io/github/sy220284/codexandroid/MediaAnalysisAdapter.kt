package io.github.sy220284.codexandroid

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** The Android layer never obtains provider credentials or calls a model HTTP endpoint. */
internal data class MediaAnalysisTarget(
    val owner: String, val provider: String, val model: String, val credentialScope: String,
    val sourceTurn: String, val epoch: Long, val inputModalities: Set<String>
) {
    val checkpointScope get() = AnalysisCheckpoint.digest("$provider\n$model\n$credentialScope")
    fun requireModalities(required: Set<String>) {
        require(inputModalities.isNotEmpty()) { "核心尚未确认分析模型输入能力" }
        val missing = MediaRoutePolicy.missing(inputModalities, required)
        require(missing.isEmpty()) { "当前模型不接收" + missing.sorted().joinToString("、") {
            when (it) { "image" -> "图片"; "audio" -> "音频"; else -> it }
        } + "，请调整媒体处理方式或选择支持该能力的模型；原附件已保留" }
    }
}

/** Independent, read-only inference adapter. Ordinary task sends still use turn/start. */
internal interface MediaAnalysisAdapter {
    suspend fun bind(owner: String, model: String, epoch: Long, permit: () -> Boolean): MediaAnalysisTarget
    suspend fun sample(target: MediaAnalysisTarget, question: String, media: JSONArray, permit: () -> Boolean): String
}

internal object MediaSampleWire {
    fun modalities(media: JSONArray): Set<String> = (0 until media.length()).mapNotNull { index ->
        when (media.getJSONObject(index).getString("type")) {
            "localImage" -> "image"; "localAudio" -> "audio"; else -> null
        }
    }.toSet()
    fun request(target: MediaAnalysisTarget, question: String, media: JSONArray): JSONObject {
        require(question.isNotBlank() && question.length <= 32_000) { "分析问题过长或为空" }
        require(media.length() in 1..32)
        val types = (0 until media.length()).map { media.getJSONObject(it).getString("type") }
        require(types.any { it == "localImage" || it == "localAudio" } &&
            types.all { it in setOf("localImage", "localAudio", "text") }) { "分析只接收本地媒体和时间轴文字" }
        return JSONObject().put("threadId", target.owner).put("expectedTurnId", target.sourceTurn)
            .put("model", target.model).put("prompt", question).put("media", media)
            .put("mediaScope", target.credentialScope).put("disableMcp", true).put("interruptOnCancel", true)
    }
    fun result(response: JSONObject, target: MediaAnalysisTarget): String {
        require(response.getString("sourceTurnId") == target.sourceTurn && response.getString("responseId").isNotBlank()) {
            "媒体分析响应归属不匹配"
        }
        return response.optString("text").takeIf { it.isNotBlank() && it != "null" && it.length <= 64_000 }
            ?: error("模型没有返回完整的文字分析；原附件已保留，未执行工具或自动重试")
    }
}

/** Native sampling inherits the actual thread provider, routing, model catalog and authentication.
 * The core intercepts a single model response and does not execute model-requested tools. */
internal class NativeMediaAnalysisAdapter(private val vm: CodexViewModel) : MediaAnalysisAdapter {
    override suspend fun bind(owner: String, model: String, epoch: Long, permit: () -> Boolean): MediaAnalysisTarget {
        check(permit()) { "分析所属会话或账号已变化" }
        val identity = vm.bridge.request("thread/sample/scope", JSONObject().put("threadId", owner)
            .apply { if (model.isNotBlank()) put("model", model) }, expectedSession = epoch, sendPermit = permit)
        val provider = identity.getString("modelProvider")
        val selected = identity.getString("model")
        val scope = identity.getString("scope")
        val kinds = identity.getJSONArray("inputModalities").let { array ->
            (0 until array.length()).map(array::getString).toSet()
        }
        require(kinds.all { it in setOf("text", "image", "audio") }) { "核心返回了未知的媒体能力" }
        require(provider.isNotBlank() && selected.isNotBlank() && scope.matches(Regex("[0-9a-f]{64}")))
        require(model.isBlank() || model == selected) { "所选模型已变化" }
        val page = vm.bridge.request("thread/turns/list", JSONObject().put("threadId", owner)
            .put("limit", 1).put("sortDirection", "desc"), expectedSession = epoch, sendPermit = permit)
        val turn = records(page).firstOrNull() ?: error("请先在此会话发送一条任务，再使用独立媒体分析")
        val turnId = turn.getString("id")
        require(turn.optString("status") in setOf("completed", "failed", "interrupted")) { "请等待当前任务结束" }
        check(permit()) { "分析所属会话或账号已变化" }
        return MediaAnalysisTarget(owner, provider, selected, scope, turnId, epoch, kinds)
    }

    override suspend fun sample(target: MediaAnalysisTarget, question: String, media: JSONArray, permit: () -> Boolean): String {
        currentCoroutineContext().ensureActive()
        target.requireModalities(MediaSampleWire.modalities(media))
        val revoked = AtomicBoolean(false)
        var requestId: Long? = null
        try {
            val response = vm.bridge.request("thread/sample", MediaSampleWire.request(target, question, media),
                timeoutMillis = 150_000L, expectedSession = target.epoch,
                sendPermit = { !revoked.get() && permit() }, onRequestId = { requestId = it })
            check(permit()) { "分析授权已结束" }
            return MediaSampleWire.result(response, target)
        } catch (cancel: CancellationException) {
            revoked.set(true)
            withContext(NonCancellable) {
                if (requestId != null && vm.bridge.connectedSession.value == target.epoch) {
                    try {
                        vm.bridge.request("thread/sample/cancel", JSONObject().put("requestId", requestId),
                            timeoutMillis = 10_000L, expectedSession = target.epoch)
                    } catch (failure: Exception) {
                        vm.errors.value = vm.errors.value + ("mediaRecovery" to
                            "核心取消回执未确认；已撤销后续发送许可，请核对连接状态")
                    }
                }
            }
            throw cancel
        } finally { revoked.set(true) }
    }
}
