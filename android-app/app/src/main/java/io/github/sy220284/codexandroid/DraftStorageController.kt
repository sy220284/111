package io.github.sy220284.codexandroid

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** All draft restoration, debounce, local persistence and safe cleanup lives here. */
internal class DraftStorageController(
    private val context: Context, private val scope: CoroutineScope
) {
    private val initial = DraftPersistence.restore(context)
    val drafts = MutableStateFlow(initial.drafts)

    fun start(report: (String, String?) -> Unit) {
        initial.warning?.let { report("draftRecovery", it) }
        if (initial.missingImages > 0) report("attachments",
            "有 ${initial.missingImages} 张旧草稿图片已失效或路径异常；文字和有效附件已保留")

        scope.launch {
            drafts.collectLatest { snapshot ->
                delay(350)
                try {
                    AppDataTransfer.persistDrafts { DraftPersistence.save(context, snapshot) }
                    report("drafts", null)
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (failure: Exception) {
                    report("drafts", failure.message ?: "本机草稿无法保存，请检查存储空间")
                }
            }
        }
        // If the original draft index is unreadable, preserve every image for
        // recovery: without its references no image can safely be called orphaned.
        if (initial.warning == null) scope.launch {
            try {
                AppDataTransfer.withLocalMutation {
                    LocalImageAttachments.cleanExpired(context, drafts.value.values.flatMap { it.images }.toSet())
                }
            } catch (failure: Exception) {
                report("attachments", failure.message ?: "无引用图片清理失败")
            }
        }
    }
}
