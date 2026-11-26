package io.github.sy220284.codexandroid

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One authorization, one native provider/model, one durable result per conversation. */
internal class MediaAnalysisController(private val vm: CodexViewModel, private val adapter: MediaAnalysisAdapter) {
    data class State(val owner: String? = null, val revision: Long = 0, val source: String? = null,
                     val mode: MediaAnalysisMode? = null, val completed: Int = 0, val total: Int = 0,
                     val busy: Boolean = false, val result: String? = null, val error: String? = null,
                     val checkpoint: AnalysisCheckpoint? = null, val scope: String? = null, val model: String = "",
                     val epoch: Long? = null, val selection: String = "")
    val state = MutableStateFlow(State())
    val results = MutableStateFlow<Map<String, State>>(emptyMap())
    private val app get() = vm.getApplication<android.app.Application>()
    private val store by lazy { AnalysisCheckpointStore(File(app.filesDir, "media-analysis-results")) }
    private var job: Job? = null
    private fun publish(value: State) {
        state.value = value
        value.owner?.let { owner -> results.update { it + (owner to value) } }
    }
    private fun restored(record: AnalysisCheckpoint, revision: Long, model: String, epoch: Long, selection: String) = State(record.owner, revision,
        record.source, MediaAnalysisMode.valueOf(record.mode), record.parts.size, record.total,
        result = record.text.takeIf { it.isNotBlank() }, checkpoint = record, scope = record.scope, model = model, epoch = epoch, selection = selection,
        error = if (record.complete) null else "分析尚未完成；已完成结果保留，可确认后继续")

    private fun valid(owner: String, revision: Long, epoch: Long) =
        vm.activeThread.value == owner && !vm.running.value &&
            vm.catalogWarmup.accountRevision.value == revision && vm.bridge.connectedSession.value == epoch

    private fun owns(value: State, owner: String) = value.owner == owner &&
        value.revision == vm.catalogWarmup.accountRevision.value && value.epoch == vm.bridge.connectedSession.value &&
        value.selection == vm.modelId.value && vm.activeThread.value == owner

    suspend fun restore(owner: String, model: String?) {
        if (model == null || state.value.busy) return
        val revision = vm.catalogWarmup.accountRevision.value
        val epoch = vm.bridge.connectedSession.value ?: return
        val selected = vm.modelId.value
        val permit = { valid(owner, revision, epoch) && vm.modelId.value == selected }
        if (!permit()) return
        results.update { it - owner }
        var scope: String? = null
        try {
            val target = adapter.bind(owner, model, epoch, permit)
            scope = target.checkpointScope
            val record = withContext(Dispatchers.IO) { store.read(owner, target.checkpointScope, false) }
            if (!permit() || state.value.busy) return
            results.update { if (record == null) it - owner else it + (owner to restored(record, revision, target.model, epoch, selected)) }
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) {
            if (scope != null && permit() && !state.value.busy) results.update { it + (owner to State(owner = owner,
                revision = revision, scope = scope, model = model, epoch = epoch, selection = selected, error = "分析记录无法读取；原记录保留，可明确清除")) }
        }
    }

    fun start(owner: String, model: String, source: String, mode: MediaAnalysisMode, question: String) {
        if (state.value.busy || job?.isActive == true || vm.composerContent.liveScreenAnalysis.state.value.running ||
            !vm.globalWorkSnapshot().canStartNewTask) return
        val revision = vm.catalogWarmup.accountRevision.value
        val epoch = vm.bridge.connectedSession.value ?: return
        val selected = vm.modelId.value
        val permit = { valid(owner, revision, epoch) && vm.modelId.value == selected &&
            source in vm.composerDrafts.value[owner]?.images.orEmpty() }
        if (!permit()) return
        publish(State(owner, revision, source, mode, busy = true, model = model, epoch = epoch, selection = selected))
        job = vm.viewModelScope.launch {
            val guard = launch {
                combine(vm.activeThread, vm.running, vm.catalogWarmup.accountRevision, vm.bridge.connectedSession, vm.modelId) { _, _, _, _, _ -> permit() }.first { !it }
                job?.cancel()
            }
            try {
                val target = adapter.bind(owner, model, epoch, permit)
                publish(state.value.copy(scope = target.checkpointScope, model = target.model))
                AppDataTransfer.withLocalMutation {
                    val saved = withContext(Dispatchers.IO) { store.read(owner, target.checkpointScope, false) }
                    require(saved == null || (saved.source == source && saved.mode == mode.name)) { "请先审阅或清除原分析记录" }
                    if (saved?.complete == true) {
                        publish(restored(saved, revision, target.model, epoch, selected))
                        return@withLocalMutation
                    }
                    require(saved == null || saved.sourceTurn == target.sourceTurn) { "会话上下文已变化，请先审阅或清除原分析记录" }
                    val prompt = saved?.question ?: question.ifBlank { "按时间顺序描述关键画面、声音及其关联。" }
                    val parts = MediaAnalysisPlan.prepare(source, LocalImageAttachments.directory(app), mode, prompt)
                    require(saved == null || (saved.total == parts.size && saved.parts.size <= parts.size)) { "媒体分段已变化，请核对原记录" }
                    var checkpoint = saved ?: AnalysisCheckpoint(owner, target.checkpointScope, source, mode.name, prompt,
                        total = parts.size, sourceTurn = target.sourceTurn)
                    publish(restored(checkpoint, revision, target.model, epoch, selected).copy(busy = true, error = null))
                    withContext(Dispatchers.IO) { store.save(checkpoint, false) }
                    LocalImageAttachments.preserveForHistory(app, listOf(source))
                    CodexForegroundService.startAndAwaitReady(app)
                    for (index in checkpoint.parts.size until parts.size) {
                        check(permit()) { "分析授权已结束" }
                        val part = parts[index]
                        val text = adapter.sample(target, part.prompt, part.input, permit)
                        checkpoint = checkpoint.copy(parts = checkpoint.parts + ("[" + part.label + "]\n" + text))
                        // Once a full paid response has returned, finish durable storage even if stop is pressed.
                        withContext(NonCancellable) {
                            withContext(Dispatchers.IO) { store.save(checkpoint, false) }
                            publish(restored(checkpoint, revision, target.model, epoch, selected).copy(busy = true, error = null))
                        }
                    }
                    publish(restored(checkpoint, revision, target.model, epoch, selected))
                }
            } catch (cancel: CancellationException) {
                publish(state.value.copy(busy = false, error = "分析已停止；已完成结果和原附件保留")); throw cancel
            } catch (failure: Exception) {
                publish(state.value.copy(busy = false, error = failure.message ?: "媒体分析失败；未自动重试"))
            } finally { guard.cancel() }
        }
    }
    fun cancel() { job?.cancel() }
    fun dismiss(owner: String) {
        val current = results.value[owner] ?: return
        if (state.value.busy || current.busy || !owns(current, owner) || !vm.globalWorkSnapshot().canStartNewTask) return
        publish(current.copy(busy = true))
        vm.viewModelScope.launch {
            try {
                AppDataTransfer.mutateLocal { current.scope?.let { store.remove(owner, it, false) } }
                results.update { it - owner }; state.value = State()
            } catch (cancel: CancellationException) { publish(current); throw cancel }
            catch (failure: Exception) { publish(current.copy(error = failure.message ?: "分析记录清除失败")) }
        }
    }
    fun apply(owner: String, textOnly: Boolean) {
        val result = results.value[owner] ?: return
        val record = result.checkpoint ?: return
        val text = record.parts.drop(record.applied).joinToString("\n\n")
        if (text.isBlank() || state.value.busy || job?.isActive == true ||
            !owns(result, owner) ||
            !vm.globalWorkSnapshot().canStartNewTask) return
        publish(result.copy(busy = true))
        vm.viewModelScope.launch {
            try {
                AppDataTransfer.withLocalMutation {
                    check(owns(result, owner)) { "分析所属账号、模型或会话已变化" }
                    val evidence = "【媒体分析资料：" + result.model + "，" + result.mode?.label + "，仅供核对】\n" + text + "\n【资料结束】"
                    vm.composerDrafts.update { drafts ->
                        val original = drafts[owner]?.text.orEmpty()
                        val updated = ThreadComposerDrafts.text(drafts, owner,
                            if (original.contains(evidence)) original else listOf(original, evidence).filter { it.isNotBlank() }.joinToString("\n\n"))
                        if (textOnly && record.complete) ThreadComposerDrafts.images(updated, owner,
                            drafts[owner]?.images.orEmpty() - record.source) else updated
                    }
                    withContext(Dispatchers.IO) { DraftPersistence.save(app, vm.composerDrafts.value) }
                    val applied = record.copy(applied = record.parts.size)
                    withContext(Dispatchers.IO) { store.save(applied, false) }
                    publish(restored(applied, result.revision, result.model, requireNotNull(result.epoch), result.selection))
                }
            } catch (cancel: CancellationException) { publish(result); throw cancel }
            catch (failure: Exception) { publish(result.copy(error = failure.message ?: "无法保存分析资料")) }
        }
    }
}
