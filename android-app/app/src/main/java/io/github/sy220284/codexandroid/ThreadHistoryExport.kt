package io.github.sy220284.codexandroid

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The prepared file is immutable, private, and owned by one export until the
 * caller closes it. A failed/cancelled preparation never hands off partial data. */
internal class ThreadExportSnapshot(val file: File, val owner: TaskRequestOwner) : AutoCloseable {
    override fun close() {
        if (file.exists() && !file.delete()) Log.w("ThreadHistoryExport", "无法清理私有导出暂存")
    }

    /** Android Binder has a shared 1 MiB transaction buffer. Leave half for
     * concurrent transactions and parcel overhead; larger text remains exportable. */
    fun clipboardText(): String = file.bufferedReader(Charsets.UTF_8).use { reader ->
        val text = StringBuilder()
        val buffer = CharArray(8192)
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            require(text.length + count <= 262144) { "完整记录超过系统剪贴板安全容量，请使用 Markdown 文件导出" }
            text.append(buffer, 0, count)
        }
        text.toString()
    }
}

internal object ThreadHistoryExport {
    suspend fun prepare(context: Context, vm: CodexViewModel, threadId: String, owner: TaskRequestOwner,
        includeActivity: Boolean, onProgress: suspend (Int) -> Unit): ThreadExportSnapshot {
        var output: File? = null
        var delivered = false
        try {
            val result = withContext(Dispatchers.IO) {
                val destination = File.createTempFile("codex-history-", ".md", context.cacheDir)
                output = destination
                val spool = File.createTempFile("codex-history-", ".spool", context.cacheDir)
                try {
                    destination.bufferedWriter(Charsets.UTF_8).use { writer ->
                        ThreadCopyHistory { method, params ->
                            owner.request(vm, method, params)
                        }.write(threadId, spool, writer, includeActivity, onProgress)
                    }
                    owner.requireCurrent(vm)
                    ThreadExportSnapshot(destination, owner)
                } finally {
                    if (!spool.delete()) Log.w("ThreadHistoryExport", "无法清理历史读取暂存")
                }
            }
            delivered = true
            return result
        } finally {
            if (!delivered) output?.let { ThreadExportSnapshot(it, owner).close() }
        }
    }
}
