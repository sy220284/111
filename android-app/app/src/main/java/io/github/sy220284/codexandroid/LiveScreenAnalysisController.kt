package io.github.sy220284.codexandroid

import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** A deliberately activated live session, not an unattended task. No requests after stop or consent loss. */
internal class LiveScreenAnalysisController(private val vm: CodexViewModel) {
    data class Observation(val startMs: Long, val endMs: Long, val text: String)
    data class State(val owner: String? = null, val token: String? = null, val revision: Long = 0,
                     val running: Boolean = false, val requests: Int = 0,
                     val observations: List<Observation> = emptyList(), val message: String = "")
    val state = MutableStateFlow(State())
    private var job: Job? = null
    @Volatile private var client: QwenMediaAnalysis? = null

    fun start(owner: String, provider: String, question: String) {
        if (job?.isActive == true || vm.composerContent.mediaAnalysis.state.value.busy || !vm.globalWorkSnapshot().canStartNewTask) return
        val share = screenShareSession.state.value
        val token = share.token ?: return
        val revision = vm.catalogWarmup.accountRevision.value
        val epoch = vm.bridge.connectedSession.value ?: return
        if (!share.capturing || share.owner != owner || share.revision != revision) return
        state.value = State(owner, token, revision, running = true)
        job = vm.viewModelScope.launch {
            val guard = launch {
                screenShareSession.state.first { it.token != token || !it.capturing }
                stop()
            }
            try {
                val app = vm.getApplication<android.app.Application>()
                val preset = DomesticModelPresets.load(app).single { it.id == "qwen-cn" }
                val row = records(vm.bridge.request("modelProvider/list", JSONObject(), expectedSession = epoch))
                    .singleOrNull { it.optString("id") == provider && preset.ownsProvider(it) }
                    ?: error("当前会话未绑定百炼工作空间")
                val endpoint = row.getString("baseUrl").trimEnd('/')
                require(preset.workspaceId(endpoint) != null && preset.id in vm.bridge.domesticCredentials.value.loaded) { "请先授权百炼工作空间" }
                val deadline = SystemClock.elapsedRealtime() + 5 * 60_000L
                val identityValid = {
                    val current = screenShareSession.state.value
                    current.capturing && current.token == token && current.owner == owner && current.revision == revision &&
                        vm.catalogWarmup.accountRevision.value == revision && vm.bridge.connectedSession.value == epoch
                }
                var sequence = 0L
                var count = 0
                while (count < 12 && SystemClock.elapsedRealtime() < deadline) {
                    if (!identityValid()) break
                    val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1)
                    val next = withTimeoutOrNull(remaining) {
                        screenShareSession.state.first { it.token != token || !it.capturing || (it.hasFrame && it.sequence > sequence) }
                    } ?: break
                    if (!next.capturing || !identityValid()) break
                    val frames = screenShareSession.framesAfter(token, owner, revision, sequence)
                    if (frames.isEmpty()) continue
                    sequence = frames.last().sequence
                    try {
                        val text = withContext(Dispatchers.IO) {
                            val cancellation = currentCoroutineContext()
                            DomesticProviderCredentials.withBoundCredential(app, preset, endpoint) { key ->
                                val active = QwenMediaAnalysis(endpoint, key) {
                                    cancellation.ensureActive()
                                    check(identityValid() && SystemClock.elapsedRealtime() < deadline) { "持续分析授权结束" }
                                }
                                client = active
                                try { active.analyzeScreen(frames, question.ifBlank { "说明当前界面变化与可见问题。" }) }
                                finally { client = null }
                            }
                        }
                        if (!identityValid()) break
                        count++
                        val observation = Observation(frames.first().timeMs, frames.last().timeMs, text)
                        state.update { it.copy(requests = count, observations = (it.observations + observation).takeLast(6)) }
                    } finally { frames.forEach { it.bytes.fill(0) } }
                    // At most one request at a time; active UI session only, no catch-up or background schedule.
                    if (count < 12) delay(10_000)
                }
                state.update { it.copy(running = false, message = "持续分析已结束；可查看最近结果") }
            } catch (cancel: CancellationException) {
                state.update { it.copy(running = false, message = "持续分析已停止") }
                throw cancel
            } catch (_: Exception) {
                state.update { it.copy(running = false, message = "持续分析中断；已保留完整返回的结果，未自动重试") }
            } finally { guard.cancel(); client = null }
        }
    }

    fun stop() { job?.cancel(); client?.cancel() }
    fun dismiss(owner: String) { if (!state.value.running && state.value.owner == owner) state.value = State() }
    fun apply(owner: String) {
        val current = state.value
        if (current.owner != owner || current.revision != vm.catalogWarmup.accountRevision.value || current.observations.isEmpty()) return
        val result = current.observations.joinToString("\n\n") { "[${it.startMs}–${it.endMs}ms]\n${it.text}" }
        vm.composerDrafts.update { drafts ->
            val old = drafts[owner]?.text.orEmpty()
            ThreadComposerDrafts.text(drafts, owner, listOf(old, "【屏幕分析资料：Qwen-Omni，仅供核对】\n$result\n【资料结束】")
                .filter { it.isNotBlank() }.joinToString("\n\n"))
        }
        if (!current.running) state.value = State()
    }
}
