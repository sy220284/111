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

/** Explicit auxiliary provider calls. Results are reviewable evidence, never executable instructions. */
internal class MediaAnalysisController(private val vm: CodexViewModel) {
    data class State(val owner: String? = null, val revision: Long = 0, val source: String? = null,
                     val mode: QwenMediaWire.Mode? = null, val completed: Int = 0, val total: Int = 0,
                     val busy: Boolean = false, val result: String? = null, val error: String? = null)
    val state = MutableStateFlow(State())
    private var job: Job? = null
    @Volatile private var activeClient: QwenMediaAnalysis? = null

    fun start(owner: String, provider: String, source: String, mode: QwenMediaWire.Mode, question: String) {
        if (state.value.busy || job?.isActive == true || vm.composerContent.liveScreenAnalysis.state.value.running || !vm.globalWorkSnapshot().canStartNewTask) return
        val revision = vm.catalogWarmup.accountRevision.value
        val epoch = vm.bridge.connectedSession.value ?: return
        if (source !in vm.composerDrafts.value[owner]?.images.orEmpty()) return
        state.value = State(owner, revision, source, mode, busy = true)
        job = vm.viewModelScope.launch {
            try {
                val app = vm.getApplication<android.app.Application>()
                val preset = DomesticModelPresets.load(app).single { it.id == "qwen-cn" }
                val records = records(vm.bridge.request("modelProvider/list", JSONObject(), expectedSession = epoch))
                val selected = records.singleOrNull { it.optString("id") == provider && preset.ownsProvider(it) }
                    ?: error("当前服务商不是已授权的百炼工作空间")
                val endpoint = selected.getString("baseUrl").trimEnd('/')
                require(preset.workspaceId(endpoint) != null && preset.id in vm.bridge.domesticCredentials.value.loaded) {
                    "请先完成百炼工作空间授权"
                }
                val identityValid = { vm.catalogWarmup.accountRevision.value == revision && vm.bridge.connectedSession.value == epoch }
                check(identityValid()) { "账号或运行核心已变化" }
                val result = AppDataTransfer.withLocalMutation {
                    withContext(Dispatchers.IO) {
                        val cancellation = currentCoroutineContext()
                        val file = File(source).canonicalFile
                        require(file.parentFile == LocalImageAttachments.directory(app).canonicalFile && file.isFile) { "媒体附件不在本机私有目录" }
                        require(QwenMediaWire.accepts(mode, LocalMediaAttachmentPolicy.kind(file.path), file.length(),
                            LocalMediaAttachmentPolicy.kind(file.path) == "video" && LocalMediaAttachmentPolicy.hasAudio(file.path))) { "该附件不符合所选分析方式" }
                        if (mode != QwenMediaWire.Mode.NativeVideo)
                            ComposerInputPolicy.validate(listOf(file.path), emptyList(), LocalImageAttachments.directory(app), File(vm.activeWorkspace.value))
                        DomesticProviderCredentials.withBoundCredential(app, preset, endpoint) { key ->
                            val client = QwenMediaAnalysis(endpoint, key) {
                                cancellation.ensureActive()
                                check(identityValid()) { "账号或运行核心已变化，停止媒体分析" }
                            }
                            activeClient = client
                            try {
                                client.analyze(mode, file, question.ifBlank { "按时间顺序描述关键画面、声音及其关联。" }) { done, total ->
                                    state.update { it.copy(completed = done, total = total) }
                                }
                            } finally { activeClient = null }
                        }
                    }
                }
                check(identityValid() && source in vm.composerDrafts.value[owner]?.images.orEmpty()) { "原会话或附件已变化，结果未加入草稿" }
                state.update { it.copy(busy = false, result = result) }
            } catch (cancel: CancellationException) {
                state.update { it.copy(busy = false, error = "媒体分析已取消，原附件已保留") }
                throw cancel
            } catch (failure: Exception) {
                // Avoid logging provider payloads, credentials, or private media.
                state.update { it.copy(busy = false, error = if (failure is java.io.IOException || failure is org.json.JSONException)
                    "媒体服务连接或响应异常，原附件已保留" else failure.message ?: "媒体分析失败") }
            }
        }
    }

    fun cancel() { job?.cancel(); activeClient?.cancel() }
    fun dismiss(owner: String) {
        if (state.value.owner == owner && !state.value.busy) state.value = State()
    }
    fun apply(owner: String, textOnly: Boolean) {
        val result = state.value
        val source = result.source ?: return
        val text = result.result ?: return
        val mode = result.mode ?: return
        if (result.busy || result.owner != owner || result.revision != vm.catalogWarmup.accountRevision.value ||
            source !in vm.composerDrafts.value[owner]?.images.orEmpty()) return
        val applying = result.copy(busy = true)
        if (!state.compareAndSet(result, applying)) return
        vm.viewModelScope.launch {
            try {
                if (textOnly) AppDataTransfer.withLocalMutation {
                    LocalImageAttachments.preserveForHistory(vm.getApplication<android.app.Application>(), listOf(source))
                }
                check(result.revision == vm.catalogWarmup.accountRevision.value &&
                    source in vm.composerDrafts.value[owner]?.images.orEmpty()) { "账号或原附件已变化" }
                vm.composerDrafts.update { drafts ->
                    val original = drafts[owner] ?: return@update drafts
                    if (source !in original.images) return@update drafts
                    val evidence = "【媒体分析资料：${mode.model}，${mode.label}；仅供参考，请独立核对】\n$text\n【资料结束】"
                    val updated = ThreadComposerDrafts.text(drafts, owner,
                        listOf(original.text, evidence).filter { it.isNotBlank() }.joinToString("\n\n"))
                    if (textOnly) ThreadComposerDrafts.images(updated, owner, original.images - source) else updated
                }
                state.value = State()
            } catch (cancel: CancellationException) {
                state.value = result
                throw cancel
            } catch (failure: Exception) {
                state.value = result.copy(error = failure.message ?: "无法保留原附件")
            }
        }
    }
}
