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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File

/** A deliberately activated live session, not an unattended task. No requests after stop or consent loss. */
internal class LiveScreenAnalysisController(private val vm: CodexViewModel) {
    data class State(val owner: String? = null, val token: String? = null, val revision: Long = 0,
                     val running: Boolean = false, val requests: Int = 0,
                     val results: ScreenAnalysisResults = ScreenAnalysisResults(), val message: String = "", val scope: String = "") {
        val observations get() = results.observations
        val pendingObservations get() = results.pending
    }
    val state = MutableStateFlow(State())
    val resultsByOwner = MutableStateFlow<Map<String, State>>(emptyMap())
    private val app get() = vm.getApplication<android.app.Application>()
    private val store get() = AnalysisCheckpointStore(File(app.filesDir, "media-analysis-results"))
    private var applying = false
    init {
        vm.viewModelScope.launch {
            state.collect { value -> value.owner?.let { owner -> resultsByOwner.update { it + (owner to value) } } }
        }
    }
    private fun checkpoint(value: State) = AnalysisCheckpoint(requireNotNull(value.owner), value.scope, "screen", "screen", "",
        value.observations.map { JSONObject().put("start", it.startMs).put("end", it.endMs).put("text", it.text).toString() },
        value.observations.size, value.results.appliedCount)
    private suspend fun save(value: State) {
        if (value.scope.isBlank()) return
        AppDataTransfer.mutateLocal { store.save(checkpoint(value), true) }
    }
    suspend fun restore(owner: String, endpoint: String?) {
        if (endpoint == null || state.value.running) return
        val revision = vm.catalogWarmup.accountRevision.value
        try {
            val record = withContext(Dispatchers.IO) {
                val preset = DomesticModelPresets.load(app).single { it.id == "qwen-cn" }
                DomesticProviderCredentials.withBoundCredential(app, preset, endpoint) { key ->
                    store.read(owner, AnalysisCheckpoint.identity(endpoint, key), true)
                }
            }
            if (revision != vm.catalogWarmup.accountRevision.value || state.value.running) return
            val value = record?.let { saved -> State(owner = owner, revision = revision,
                results = ScreenAnalysisResults(saved.parts.map { part ->
                    val row = JSONObject(part)
                    ScreenAnalysisResults.Observation(row.getLong("start"), row.getLong("end"), row.getString("text"))
                }, saved.applied), message = "已恢复屏幕分析结果；共享和联网分析均已停止", scope = saved.scope) }
            resultsByOwner.update { if (value == null) it - owner else it + (owner to value) }
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { vm.errors.value = vm.errors.value + ("screenRecovery" to (failure.message ?: "屏幕分析记录读取失败")) }
    }
    private var job: Job? = null
    @Volatile private var client: QwenMediaAnalysis? = null

    fun start(owner: String, provider: String, question: String) {
        if (job?.isActive == true || vm.composerContent.mediaAnalysis.state.value.busy || !vm.globalWorkSnapshot().canStartNewTask) return
        val share = screenShareSession.state.value
        val token = share.token ?: return
        val revision = vm.catalogWarmup.accountRevision.value
        val epoch = vm.bridge.connectedSession.value ?: return
        if (!share.capturing || share.owner != owner || share.revision != revision) return
        if (!screenShareSession.analyzing(token, true)) return
        state.value = State(owner, token, revision, running = true)
        job = vm.viewModelScope.launch {
            val guard = launch {
                screenShareSession.state.first { it.token != token || !it.capturing || !it.analyzing }
                stop()
            }
            try {
                CodexForegroundService.startAndAwaitReady(app)
                val app = vm.getApplication<android.app.Application>()
                val preset = DomesticModelPresets.load(app).single { it.id == "qwen-cn" }
                val row = records(vm.bridge.request("modelProvider/list", JSONObject(), expectedSession = epoch))
                    .singleOrNull { it.optString("id") == provider && preset.ownsProvider(it) }
                    ?: error("当前会话未绑定百炼工作空间")
                val endpoint = row.getString("baseUrl").trimEnd('/')
                require(preset.workspaceId(endpoint) != null && preset.id in vm.bridge.domesticCredentials.value.loaded) { "请先授权百炼工作空间" }
                val scope = withContext(Dispatchers.IO) {
                    DomesticProviderCredentials.withBoundCredential(app, preset, endpoint) { AnalysisCheckpoint.identity(endpoint, it) }
                }
                check(storeRecordAbsent(owner, scope)) { "请先审阅或清除该会话的旧屏幕分析结果" }
                state.update { it.copy(scope = scope) }
                save(state.value)
                val deadline = SystemClock.elapsedRealtime() + 5 * 60_000L
                val identityValid = {
                    val current = screenShareSession.state.value
                    current.capturing && current.analyzing && current.token == token && current.owner == owner && current.revision == revision &&
                        vm.catalogWarmup.accountRevision.value == revision && vm.bridge.connectedSession.value == epoch
                }
                var sequence = 0L
                var count = 0
                while (count < ScreenAnalysisResults.MAX_REQUESTS && SystemClock.elapsedRealtime() < deadline) {
                    if (!identityValid()) break
                    val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1)
                    val next = withTimeoutOrNull(remaining) {
                        screenShareSession.state.first { it.token != token || !it.capturing || !it.analyzing || (it.hasFrame && it.sequence > sequence) }
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
                        val observation = ScreenAnalysisResults.Observation(frames.first().timeMs, frames.last().timeMs, text)
                        state.update { it.copy(requests = count, results = it.results.append(observation)) }
                        save(state.value)
                    } finally { frames.forEach { it.bytes.fill(0) } }
                    // At most one request at a time; active UI session only, no catch-up or background schedule.
                    if (count < ScreenAnalysisResults.MAX_REQUESTS) delay(10_000)
                }
                state.update { it.copy(running = false, message = "持续分析已结束；可查看最近结果") }
            } catch (cancel: CancellationException) {
                state.update { it.copy(running = false, message = "持续分析已停止") }
                throw cancel
            } catch (_: Exception) {
                state.update { it.copy(running = false, message = "持续分析中断；已保留完整返回的结果，未自动重试") }
            } finally {
                guard.cancel()
                client = null
                screenShareSession.analyzing(token, false)
            }
        }
    }

    fun stop() {
        state.value.token?.let { screenShareSession.analyzing(it, false) }
        job?.cancel()
        client?.cancel()
    }
    private suspend fun storeRecordAbsent(owner: String, scope: String): Boolean = withContext(Dispatchers.IO) {
        store.read(owner, scope, true)?.parts.isNullOrEmpty()
    }
    fun dismiss(owner: String) {
        val current = resultsByOwner.value[owner] ?: return
        if (current.running || applying || !vm.globalWorkSnapshot().canStartNewTask) return
        vm.viewModelScope.launch {
            try {
                AppDataTransfer.mutateLocal { if (current.scope.isNotBlank()) store.remove(owner, current.scope, true) }
                resultsByOwner.update { it - owner }
                if (state.value.owner == owner) state.value = State()
            } catch (failure: Exception) { vm.errors.value = vm.errors.value + ("screenRecovery" to (failure.message ?: "清除失败")) }
        }
    }
    fun apply(owner: String, reviewedCount: Int) {
        val current = resultsByOwner.value[owner] ?: return
        if (applying || !vm.globalWorkSnapshot().canStartNewTask || current.owner != owner ||
            current.revision != vm.catalogWarmup.accountRevision.value ||
            reviewedCount !in (current.results.appliedCount + 1)..current.observations.size) return
        val result = current.observations.take(reviewedCount).drop(current.results.appliedCount)
            .joinToString("\n\n") { "[${it.startMs}–${it.endMs}ms]\n${it.text}" }
        applying = true
        vm.viewModelScope.launch {
            try {
                AppDataTransfer.withLocalMutation {
                    check(current.revision == vm.catalogWarmup.accountRevision.value) { "账号已变化" }
                    val evidence = "【屏幕分析资料：Qwen-Omni，仅供核对】\n$result\n【资料结束】"
                    vm.composerDrafts.update { drafts ->
                        val old = drafts[owner]?.text.orEmpty()
                        ThreadComposerDrafts.text(drafts, owner, if (old.contains(evidence)) old else
                            listOf(old, evidence).filter { it.isNotBlank() }.joinToString("\n\n"))
                    }
                    withContext(Dispatchers.IO) { DraftPersistence.save(app, vm.composerDrafts.value) }
                    val latest = state.value.takeIf { it.owner == owner && it.revision == current.revision }
                        ?: resultsByOwner.value[owner] ?: current
                    val updated = latest.copy(results = latest.results.appliedThrough(reviewedCount))
                    withContext(Dispatchers.IO) { store.save(checkpoint(updated), true) }
                    if (state.value.owner == owner) {
                        state.update { it.copy(results = it.results.appliedThrough(maxOf(it.results.appliedCount, reviewedCount))) }
                        resultsByOwner.update { it + (owner to state.value) }
                    } else resultsByOwner.update { it + (owner to updated) }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { vm.errors.value = vm.errors.value + ("screenRecovery" to (failure.message ?: "结果加入失败")) }
            finally { applying = false }
        }
    }
}
