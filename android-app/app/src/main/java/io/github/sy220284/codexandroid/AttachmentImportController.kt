package io.github.sy220284.codexandroid

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A picker result belongs to its initiating conversation, even after navigation. */
data class AttachmentImportState(
    val threadKey: String = ThreadComposerDrafts.NEW_THREAD,
    val inProgress: Boolean = false,
    val imported: Int = 0,
    val requested: Int = 0,
    val message: String = ""
)

/** App-session owned attachment work: UI only launches the system picker and renders state. */
internal class AttachmentImportController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val drafts: MutableStateFlow<Map<String, ComposerDraft>>,
    private val errors: MutableStateFlow<Map<String, String>>,
    private val activeImages: () -> List<String>
) {
    val state = MutableStateFlow(AttachmentImportState())
    private var pickerOwner: String? = null
    private var pickerInFlight = false

    fun editText(threadId: String?, text: String) {
        drafts.update { ThreadComposerDrafts.text(it, threadId, text) }
    }

    fun editImages(threadId: String?, paths: List<String>) {
        drafts.update { ThreadComposerDrafts.images(it, threadId, paths) }
    }

    fun clearAfterAccepted(threadId: String?, text: String, images: List<String>) {
        drafts.update { ThreadComposerDrafts.accepted(it, threadId, text, images) }
    }

    fun beginImageSelection(threadId: String?): Boolean {
        if (pickerInFlight || state.value.inProgress) {
            state.value = state.value.copy(message = "请先完成当前选图或等待导入结束")
            return false
        }
        pickerInFlight = true
        pickerOwner = threadId
        return true
    }

    fun importPickedImages(selected: List<Uri>) {
        if (!pickerInFlight) return
        val owner = pickerOwner
        pickerOwner = null
        pickerInFlight = false
        if (selected.isEmpty()) return
        val key = ThreadComposerDrafts.key(owner)
        if (state.value.inProgress) {
            state.value = state.value.copy(message = "还有图片正在导入，请等本次完成后重新选择")
            return
        }
        val remaining = (LocalImageAttachments.MAX_FILES -
            drafts.value[key]?.images.orEmpty().size).coerceAtLeast(0)
        val accepted = selected.take(remaining)
        if (accepted.isEmpty()) {
            state.value = AttachmentImportState(
                threadKey = key, message = "每条会话最多添加 3 张图片，请先移除一张")
            return
        }
        state.value = AttachmentImportState(
            threadKey = key, inProgress = true, requested = accepted.size)
        scope.launch {
            val failures = mutableListOf<String>()
            var imported = 0
            try {
                AppDataTransfer.withLocalMutation { coroutineScope {
                    // At most three independent URI copies are started together. Results are
                    // committed in selection order so the draft stays predictable.
                    val tasks = accepted.map { uri ->
                        async(Dispatchers.IO) {
                            try { Result.success(LocalImageAttachments.importFrom(context, uri)) }
                            catch (cancel: CancellationException) { throw cancel }
                            catch (failure: Exception) { Result.failure(failure) }
                        }
                    }
                    for (task in tasks) {
                        val result = task.await()
                        try {
                            val path = result.getOrThrow()
                            drafts.update { previous ->
                                val current = previous[key]?.images.orEmpty()
                                if (current.size >= LocalImageAttachments.MAX_FILES) previous
                                else ThreadComposerDrafts.images(previous, owner, current + path)
                            }
                            if (path in drafts.value[key]?.images.orEmpty()) imported++
                            else LocalImageAttachments.release(context, path)
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (failure: Exception) {
                            failures += (failure.message ?: "无法导入所选图片")
                        }
                        state.value = state.value.copy(imported = imported)
                    }
                } }
            } catch (cancel: CancellationException) {
                state.value = AttachmentImportState(threadKey = key, requested = accepted.size, imported = imported,
                    message = "图片导入中断，已保留成功导入的图片")
                throw cancel
            } catch (failure: Exception) {
                failures += (failure.message ?: "图片导入失败")
            }
            val skipped = selected.size - accepted.size
            state.value = AttachmentImportState(
                threadKey = key, requested = accepted.size, imported = imported,
                message = when {
                    failures.isNotEmpty() -> "已导入 ${imported} 张；" +
                        failures.distinct().joinToString("；")
                    skipped > 0 -> "已导入 ${imported} 张，另有 ${skipped} 张超出剩余名额"
                    else -> ""
                }
            )
        }
    }

    fun removeComposerImage(threadId: String?, path: String) {
        drafts.update { previous ->
            val current = previous[ThreadComposerDrafts.key(threadId)]?.images.orEmpty()
            ThreadComposerDrafts.images(previous, threadId, current - path)
        }
        scope.launch {
            try {
                // A removed draft photo may still be read by the currently
                // executing Codex turn. The turn completion path releases it.
                if (AttachmentRetentionPolicy.canRelease(
                        path, drafts.value.values, activeImages()
                    )) AppDataTransfer.mutateLocal { LocalImageAttachments.release(context, path) }
                errors.value = errors.value - "attachments"
            } catch (failure: Exception) {
                errors.value = errors.value + ("attachments" to
                    (failure.message ?: "无法清理该图片缓存"))
            }
        }
    }

    fun reportFullAttachmentSelection(threadId: String?) {
        state.value = AttachmentImportState(
            threadKey = ThreadComposerDrafts.key(threadId),
            message = "每条会话最多添加 3 张图片，请先移除一张"
        )
    }
}
