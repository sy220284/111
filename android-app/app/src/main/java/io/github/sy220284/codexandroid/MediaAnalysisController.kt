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

/** Independent durable records, keyed by attachment, processing intention, question and source turn. */
internal class MediaAnalysisController(private val vm: CodexViewModel, private val adapter: MediaAnalysisAdapter) {
    data class RecordSummary(val identity: String, val label: String)
    private fun summary(record: AnalysisCheckpoint) = RecordSummary(record.identity,
        (MediaAnalysisMode.entries.firstOrNull { it.name == record.mode }?.label ?: record.mode) +
            " · " + File(record.source).extension.uppercase(java.util.Locale.ROOT) + " · " +
            record.parts.size + "/" + record.total + " · " + record.question.take(36))
    data class State(val owner: String? = null, val revision: Long = 0, val source: String? = null,
                     val mode: MediaAnalysisMode? = null, val completed: Int = 0, val total: Int = 0,
                     val busy: Boolean = false, val result: String? = null, val error: String? = null,
                     val checkpoint: AnalysisCheckpoint? = null, val scope: String? = null, val model: String = "",
                     val epoch: Long? = null, val selection: String = "", val history: List<RecordSummary> = emptyList())
    val state = MutableStateFlow(State())
    val results = MutableStateFlow<Map<String, State>>(emptyMap())
    private val app get() = vm.getApplication<android.app.Application>()
    private val store by lazy { AnalysisCheckpointStore(File(app.filesDir, "media-analysis-results")) }
    private var job: Job? = null
    private fun publish(value: State) {
        val prior = value.owner?.let { results.value[it] }
        val history = value.history.ifEmpty { prior?.takeIf { it.scope == value.scope && it.revision == value.revision && it.epoch == value.epoch }?.history.orEmpty() }
        val record = value.checkpoint
        val next = value.copy(history = if (record == null) history else listOf(summary(record)) + history.filterNot { it.identity == record.identity })
        state.value = next
        next.owner?.let { owner -> results.update { it + (owner to next) } }
    }
    private fun restored(record: AnalysisCheckpoint, revision: Long, model: String, epoch: Long, selection: String) = State(record.owner, revision,
        record.source, MediaAnalysisMode.valueOf(record.mode), record.parts.size, record.total,
        result = record.text.takeIf { it.isNotBlank() }, checkpoint = record, scope = record.scope, model = model, epoch = epoch, selection = selection,
        error = if (record.complete) null else "分析尚未完成；已完成结果保留，可确认后继续")

    private fun valid(owner: String, revision: Long, epoch: Long) =
        vm.composerOwner(vm.activeThread.value) == owner && !vm.running.value && vm.conversationState.value.remote == null &&
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
            val records = withContext(Dispatchers.IO) { store.listMedia(owner, target.checkpointScope) }
            if (!permit() || state.value.busy) return
            val record = records.firstOrNull()
            results.update { if (record == null) it - owner else it + (owner to restored(record, revision, target.model, epoch, selected).copy(history = records.map(::summary))) }
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) {
            if (scope != null && permit() && !state.value.busy) results.update { it + (owner to State(owner = owner,
                revision = revision, scope = scope, model = model, epoch = epoch, selection = selected, error = "分析记录无法读取；原记录保留，可明确清除")) }
        }
    }

    fun start(owner: String, model: String, source: String, mode: MediaAnalysisMode, question: String, resume: Boolean = false) {
        if (state.value.busy || job?.isActive == true || vm.composerContent.liveScreenAnalysis.state.value.running ||
            vm.firstThreadStarting.value || vm.threadSwitching.value || vm.securitySaveBusy.value ||
            !vm.globalWorkSnapshot().canStartNewTask) return
        val taskOwner = vm.captureTaskOwner() ?: return
        val revision = taskOwner.account
        val epoch = taskOwner.epoch
        val selected = vm.modelId.value
        val creating = vm.activeThread.value == null
        var boundOwner = owner
        val permit = { valid(boundOwner, revision, epoch) && vm.modelId.value == selected &&
            source in vm.composerDrafts.value[boundOwner]?.images.orEmpty() }
        if (!permit()) return
        val previous = results.value[owner]?.takeIf { owns(it, owner) }
        val resumeRecord = if (resume) previous?.checkpoint else null
        if (resume && (resumeRecord == null || resumeRecord.source != source || resumeRecord.mode != mode.name)) return
        if (creating) vm.firstThreadStarting.value = true
        publish(State(owner, revision, source, mode, busy = true, model = model, epoch = epoch, selection = selected,
            scope = previous?.scope, history = previous?.history.orEmpty()))
        job = vm.viewModelScope.launch {
            var guard: Job? = null
            var draftGuard: Job? = null
            try {
                if (creating) {
                    try {
                        vm.createMediaAnalysisSource(owner, taskOwner) { id ->
                            boundOwner = id
                            results.update { it - owner }
                            publish(state.value.copy(owner = id))
                        }
                    } finally { vm.firstThreadStarting.value = false }
                }
                check(permit()) { "分析授权已结束" }
                guard = launch {
                    combine(vm.activeThread, vm.running, vm.catalogWarmup.accountRevision, vm.bridge.connectedSession, vm.modelId) { _, _, _, _, _ -> permit() }.first { !it }
                    job?.cancel()
                }
                draftGuard = launch { vm.composerDrafts.first { !permit() }; job?.cancel() }
                val target = adapter.bind(boundOwner, model, epoch, permit, fresh = creating)
                publish(state.value.copy(scope = target.checkpointScope, model = target.model))
                AppDataTransfer.withLocalMutation {
                    val prompt = resumeRecord?.question ?: question.ifBlank { "按时间顺序描述关键画面、声音及其关联。" }
                    val identity = AnalysisCheckpoint(boundOwner, target.checkpointScope, source, mode.name, prompt, sourceTurn = target.sourceTurn).identity
                    if (resumeRecord != null) require(resumeRecord.identity == identity && resumeRecord.scope == target.checkpointScope) {
                        "会话上下文已变化，原记录可查看；重新分析需再次确认"
                    }
                    val saved = withContext(Dispatchers.IO) { store.readMedia(boundOwner, target.checkpointScope, identity) }
                    if (saved?.complete == true) {
                        publish(restored(saved, revision, target.model, epoch, selected))
                        return@withLocalMutation
                    }
                    require(saved == null || saved.sourceTurn == target.sourceTurn) { "会话上下文已变化，请先审阅或清除原分析记录" }
                    // Resolve known incompatibility before expensive decoding or any model request.
                    val required = withContext(Dispatchers.IO) {
                        MediaAnalysisPlan.requiredModalities(LocalMediaAttachmentPolicy.kind(source),
                            LocalMediaAttachmentPolicy.hasAudio(source), mode)
                    }
                    target.requireModalities(required)
                    val parts = MediaAnalysisPlan.prepare(source, LocalImageAttachments.directory(app), mode, prompt)
                    target.requireModalities(MediaAnalysisPlan.modalities(parts))
                    require(saved == null || (saved.total == parts.size && saved.parts.size <= parts.size)) { "媒体分段已变化，请核对原记录" }
                    var checkpoint = saved ?: AnalysisCheckpoint(boundOwner, target.checkpointScope, source, mode.name, prompt,
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
            } finally { guard?.cancel(); draftGuard?.cancel() }
        }
    }
    fun cancel() { job?.cancel() }

    fun selectRecord(owner: String, identity: String) {
        val current = results.value[owner] ?: return
        val scope = current.scope ?: return
        if (state.value.busy || !owns(current, owner)) return
        publish(current.copy(busy = true))
        vm.viewModelScope.launch {
            try {
                val record = withContext(Dispatchers.IO) { store.readMedia(owner, scope, identity) }
                    ?: error("分析记录已不存在")
                if (owns(current, owner)) publish(restored(record, current.revision, current.model,
                    requireNotNull(current.epoch), current.selection).copy(history = current.history))
                else state.value = State()
            } catch (cancel: CancellationException) { publish(current); throw cancel }
            catch (failure: Exception) { publish(current.copy(error = failure.message ?: "无法打开分析记录")) }
        }
    }
    fun dismiss(owner: String) {
        val current = results.value[owner] ?: return
        if (state.value.busy || current.busy || !owns(current, owner) || !vm.globalWorkSnapshot().canStartNewTask) return
        publish(current.copy(busy = true))
        vm.viewModelScope.launch {
            try {
                val scope = requireNotNull(current.scope)
                val record = current.checkpoint
                AppDataTransfer.mutateLocal {
                    if (record != null) store.removeMedia(record) else store.removeDamagedMedia(owner, scope)
                }
                val remaining = withContext(Dispatchers.IO) { store.listMedia(owner, scope) }
                results.update { it - owner }; state.value = State()
                if (owns(current, owner) && remaining.isNotEmpty())
                    publish(restored(remaining.first(), current.revision, current.model, requireNotNull(current.epoch), current.selection)
                        .copy(history = remaining.map(::summary)))
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
