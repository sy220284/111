package io.github.sy220284.codexandroid

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
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
    private val errors: MutableStateFlow<Map<String, String>>
) {
    val state = MutableStateFlow(AttachmentImportState())
    private var pickerOwner: String? = null

    fun beginImageSelection(threadId: String?) {
        pickerOwner = threadId
    }

    fun importPickedImages(selected: List<Uri>) {
        val owner = pickerOwner
        pickerOwner = null
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
            for (uri in accepted) {
                try {
                    val path = LocalImageAttachments.importFrom(context, uri)
                    drafts.update { previous ->
                        val current = previous[key]?.images.orEmpty()
                        if (current.size >= LocalImageAttachments.MAX_FILES) previous
                        else ThreadComposerDrafts.images(previous, owner, current + path)
                    }
                    if (path in drafts.value[key]?.images.orEmpty()) imported++
                    else LocalImageAttachments.release(context, path)
                } catch (failure: Exception) {
                    failures += (failure.message ?: "无法导入所选图片")
                }
                state.value = state.value.copy(imported = imported)
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
                LocalImageAttachments.release(context, path)
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
