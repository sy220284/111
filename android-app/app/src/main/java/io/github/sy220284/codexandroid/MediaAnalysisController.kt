package io.github.sy220284.codexandroid

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Explicit provider work shares durable per-owner checkpoints and never replaces another conversation's result. */
internal class MediaAnalysisController(private val vm: CodexViewModel) {
    data class State(val owner: String? = null, val revision: Long = 0, val source: String? = null,
                     val mode: QwenMediaWire.Mode? = null, val completed: Int = 0, val total: Int = 0,
                     val busy: Boolean = false, val result: String? = null, val error: String? = null,
                     val checkpoint: AnalysisCheckpoint? = null, val scope: String? = null)
    val state = MutableStateFlow(State())
    val results = MutableStateFlow<Map<String, State>>(emptyMap())
    private val app get() = vm.getApplication<android.app.Application>()
    private val store by lazy { AnalysisCheckpointStore(File(app.filesDir, "media-analysis-results")) }
    private var job: Job? = null
    @Volatile private var activeClient: QwenMediaAnalysis? = null
    private fun publish(value: State) {
        state.value = value
        value.owner?.let { owner -> results.update { it + (owner to value) } }
    }
    private fun restored(record: AnalysisCheckpoint, revision: Long) = State(record.owner, revision,
        record.source, QwenMediaWire.Mode.valueOf(record.mode), record.parts.size, record.total,
        result = record.text.takeIf { it.isNotBlank() }, checkpoint = record, scope = record.scope,
        error = if (record.complete) null else "分析尚未全部完成；已完成结果已保留，可确认后继续剩余部分")

    suspend fun restore(owner: String, endpoint: String?) {
        if (endpoint == null || state.value.busy) return
        val revision = vm.catalogWarmup.accountRevision.value
        var scope: String? = null
        try {
            val record = withContext(Dispatchers.IO) {
                val preset = DomesticModelPresets.load(app).single { it.id == "qwen-cn" }
                DomesticProviderCredentials.withBoundCredential(app, preset, endpoint) { key ->
                    val identity = AnalysisCheckpoint.identity(endpoint, key)
                    scope = identity
                    store.read(owner, identity, false)
                }
            }
            if (revision != vm.catalogWarmup.accountRevision.value || state.value.busy) return
            results.update { if (record == null) it - owner else it + (owner to restored(record, revision)) }
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { results.update { it + (owner to State(owner = owner, revision = revision, scope = scope,
            error = "分析记录无法读取，原记录已保留；可清除后重新分析")) } }
    }

    fun start(owner: String, provider: String, source: String, mode: QwenMediaWire.Mode, question: String) {
        if (state.value.busy || job?.isActive == true || vm.composerContent.liveScreenAnalysis.state.value.running || !vm.globalWorkSnapshot().canStartNewTask) return
        val revision = vm.catalogWarmup.accountRevision.value
        val epoch = vm.bridge.connectedSession.value ?: return
        if (source !in vm.composerDrafts.value[owner]?.images.orEmpty()) return
        val existing = results.value[owner]?.takeIf { it.revision == revision }
        if (existing?.checkpoint?.complete == true) return
        publish(State(owner, revision, source, mode, busy = true, checkpoint = existing?.checkpoint))
        job = vm.viewModelScope.launch {
            try {
                CodexForegroundService.startAndAwaitReady(app)
                val preset = DomesticModelPresets.load(app).single { it.id == "qwen-cn" }
                val selected = records(vm.bridge.request("modelProvider/list", JSONObject(), expectedSession = epoch))
                    .singleOrNull { it.optString("id") == provider && preset.ownsProvider(it) }
                    ?: error("当前服务商不是已授权的百炼工作空间")
                val endpoint = selected.getString("baseUrl").trimEnd('/')
                val identityValid = { vm.catalogWarmup.accountRevision.value == revision && vm.bridge.connectedSession.value == epoch }
                check(identityValid()) { "账号或运行核心已变化" }
                AppDataTransfer.withLocalMutation {
                    withContext(Dispatchers.IO) {
                        val cancellation = currentCoroutineContext()
                        val file = File(source).canonicalFile
                        require(file.parentFile == LocalImageAttachments.directory(app).canonicalFile && file.isFile) { "媒体附件不在本机私有目录" }
                        require(QwenMediaWire.accepts(mode, LocalMediaAttachmentPolicy.kind(file.path), file.length(), LocalMediaAttachmentPolicy.hasAudio(file.path))) { "该附件不符合所选分析方式" }
                        if (mode != QwenMediaWire.Mode.NativeVideo) LocalMediaAttachmentPolicy.prepare(listOf(source), LocalImageAttachments.directory(app)) { cancellation.ensureActive() }
                        LocalImageAttachments.preserveForHistory(app, listOf(source))
                        DomesticProviderCredentials.withBoundCredential(app, preset, endpoint) { key ->
                            val scope = AnalysisCheckpoint.identity(endpoint, key)
                            val saved = store.read(owner, scope, false)
                            require(saved == null || (saved.source == source && saved.mode == mode.name)) { "请先审阅或清除原分析记录" }
                            var checkpoint = saved ?: AnalysisCheckpoint(owner, scope, source, mode.name,
                                question.ifBlank { "按时间顺序描述关键画面、声音及其关联。" })
                            store.save(checkpoint, false)
                            val client = QwenMediaAnalysis(endpoint, key) {
                                cancellation.ensureActive(); check(identityValid()) { "账号或运行核心已变化，停止媒体分析" }
                            }
                            activeClient = client
                            try {
                                client.analyze(mode, file, checkpoint.question, checkpoint.parts,
                                    onCompleted = { index, text ->
                                        check(index == checkpoint.parts.size) { "分析段落回执顺序错误" }
                                        checkpoint = checkpoint.copy(parts = checkpoint.parts + text)
                                        val snapshot = restored(checkpoint, revision).copy(busy = true, error = null)
                                        results.update { it + (owner to snapshot) }; state.value = snapshot
                                        store.save(checkpoint, false)
                                    }) { done, total ->
                                    checkpoint = checkpoint.copy(total = total)
                                    store.save(checkpoint, false)
                                    val snapshot = restored(checkpoint, revision).copy(busy = true, completed = done, error = null)
                                    results.update { it + (owner to snapshot) }; state.value = snapshot
                                }
                            } finally { activeClient = null }
                        }
                    }
                }
                publish(state.value.copy(busy = false, error = null))
            } catch (cancel: CancellationException) {
                publish(state.value.copy(busy = false, error = "分析已停止，已完成结果和原附件已保留")); throw cancel
            } catch (failure: Exception) {
                publish(state.value.copy(busy = false, error = if (failure is java.io.IOException || failure is org.json.JSONException)
                    "媒体服务或记录保存异常，已完成结果和原附件已保留" else failure.message ?: "媒体分析失败"))
            }
        }
    }
    fun cancel() { job?.cancel(); activeClient?.cancel() }
    fun dismiss(owner: String) {
        val current = results.value[owner] ?: return
        if (current.busy || !vm.globalWorkSnapshot().canStartNewTask) return
        vm.viewModelScope.launch {
            try {
                AppDataTransfer.mutateLocal { (current.checkpoint?.scope ?: current.scope)?.let { store.remove(owner, it, false) } }
                results.update { it - owner }; if (state.value.owner == owner) state.value = State()
            } catch (failure: Exception) { vm.errors.value = vm.errors.value + ("mediaRecovery" to (failure.message ?: "分析记录清除失败")) }
        }
    }
    fun apply(owner: String, textOnly: Boolean) {
        val result = results.value[owner] ?: return
        val source = result.source ?: return
        val text = result.checkpoint?.let { it.parts.drop(it.applied).joinToString("\n\n").takeIf(String::isNotBlank) }
            ?: if (result.checkpoint == null) result.result else null
        if (text == null) return
        val removeMedia = textOnly && result.checkpoint?.complete != false
        val mode = result.mode ?: return
        if (state.value.busy || job?.isActive == true || result.busy || result.revision != vm.catalogWarmup.accountRevision.value || !vm.globalWorkSnapshot().canStartNewTask ||
            source !in vm.composerDrafts.value[owner]?.images.orEmpty()) return
        publish(result.copy(busy = true))
        vm.viewModelScope.launch {
            try {
                AppDataTransfer.withLocalMutation {
                    if (removeMedia) LocalImageAttachments.preserveForHistory(app, listOf(source))
                    check(result.revision == vm.catalogWarmup.accountRevision.value) { "账号已变化" }
                    vm.composerDrafts.update { drafts ->
                        val original = drafts[owner] ?: return@update drafts
                        if (source !in original.images) return@update drafts
                        val evidence = "【媒体分析资料：${mode.model}，${mode.label}；${if (result.checkpoint?.complete == true) "已完成" else "部分完成"}，仅供核对】\n$text\n【资料结束】"
                        val updated = ThreadComposerDrafts.text(drafts, owner,
                            if (original.text.contains(evidence)) original.text else
                                listOf(original.text, evidence).filter { it.isNotBlank() }.joinToString("\n\n"))
                        if (removeMedia) ThreadComposerDrafts.images(updated, owner, original.images - source) else updated
                    }
                    withContext(Dispatchers.IO) { DraftPersistence.save(app, vm.composerDrafts.value) }
                    result.checkpoint?.let { record -> withContext(Dispatchers.IO) {
                        if (record.complete) store.remove(owner, record.scope, false)
                        else store.save(record.copy(applied = record.parts.size), false)
                    } }
                }
                val record = result.checkpoint
                if (record != null && !record.complete) publish(restored(record.copy(applied = record.parts.size), result.revision))
                else { results.update { it - owner }; state.value = State() }
            } catch (cancel: CancellationException) { publish(result); throw cancel }
            catch (failure: Exception) { publish(result.copy(error = failure.message ?: "无法保存分析资料")) }
        }
    }
}
