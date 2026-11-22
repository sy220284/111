package io.github.sy220284.codexandroid

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Local-only record of decisions actually delivered to the Codex core. */
internal data class ApprovalAuditRecord(
    val timeMs: Long,
    val operation: String,
    val decision: String,
    val summary: String
)

internal object ApprovalAuditCodec {
    const val LIMIT = 100

    fun serialize(records: List<ApprovalAuditRecord>): String {
        val values = JSONArray()
        records.take(LIMIT).forEach { record ->
            values.put(JSONObject()
                .put("timeMs", record.timeMs)
                .put("operation", record.operation)
                .put("decision", record.decision)
                .put("summary", record.summary))
        }
        return values.toString()
    }

    fun deserialize(json: String): List<ApprovalAuditRecord> {
        val source = JSONArray(json)
        return (0 until minOf(source.length(), LIMIT)).map { index ->
            val record = source.getJSONObject(index)
            ApprovalAuditRecord(
                timeMs = record.getLong("timeMs"),
                operation = record.getString("operation"),
                decision = record.getString("decision"),
                summary = record.getString("summary")
            )
        }
    }

    fun describeOperation(method: String): String = when (method) {
        "item/commandExecution/requestApproval" -> "执行命令"
        "item/fileChange/requestApproval" -> "修改文件"
        "item/permissions/requestApproval" -> "扩展权限"
        else -> "其他授权"
    }

    fun describeDecision(value: String): String = when (value) {
        "accept" -> "允许本次"
        "acceptForSession" -> "本会话允许"
        "decline" -> "已拒绝"
        "cancel" -> "已取消"
        else -> "已处理"
    }

    fun from(prompt: ServerPrompt, decision: String, now: Long): ApprovalAuditRecord {
        val text = prompt.params.optString("command")
            .ifBlank { prompt.params.optString("reason") }
            .ifBlank { describeOperation(prompt.method) }
        return ApprovalAuditRecord(
            timeMs = now, operation = describeOperation(prompt.method),
            decision = describeDecision(decision), summary = text.take(1000)
        )
    }
}

/**
 * Atomic private storage with bounded history. All disk mutations use one mutex,
 * so rapid replies and a simultaneous Clear cannot silently overwrite each other.
 */
internal class ApprovalAuditController(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val file = AtomicFile(File(context.filesDir, "approval-audit.json"))

    val records = MutableStateFlow<List<ApprovalAuditRecord>>(emptyList())
    val error = MutableStateFlow<String?>(null)

    init {
        scope.launch {
            lock.withLock {
                try {
                    records.value = read()
                    error.value = null
                } catch (failure: Exception) {
                    error.value = "读取本机审批记录失败：" +
                        (failure.message ?: "记录文件无法解析")
                }
            }
        }
    }

    private fun read(): List<ApprovalAuditRecord> {
        if (!file.baseFile.exists()) return emptyList()
        return file.openRead().bufferedReader(Charsets.UTF_8).use {
            ApprovalAuditCodec.deserialize(it.readText())
        }
    }

    private fun write(value: List<ApprovalAuditRecord>) {
        val stream = file.startWrite()
        try {
            stream.write(ApprovalAuditCodec.serialize(value).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (failure: Exception) {
            file.failWrite(stream)
            throw failure
        }
    }

    /** A write failure is displayed separately: the approval RPC was already delivered. */
    suspend fun record(prompt: ServerPrompt, decision: String) {
        if (prompt.method !in setOf(
                "item/commandExecution/requestApproval",
                "item/fileChange/requestApproval",
                "item/permissions/requestApproval")) return
        withContext(Dispatchers.IO) {
            lock.withLock {
                try {
                    val updated = (listOf(ApprovalAuditCodec.from(prompt, decision,
                        System.currentTimeMillis())) + records.value)
                        .take(ApprovalAuditCodec.LIMIT)
                    write(updated)
                    records.value = updated
                    error.value = null
                } catch (failure: Exception) {
                    error.value = "授权已回复内核，但审批记录未保存：" +
                        (failure.message ?: "设备存储异常")
                }
            }
        }
    }

    fun clear() {
        scope.launch {
            lock.withLock {
                try {
                    file.delete()
                    records.value = emptyList()
                    error.value = null
                } catch (failure: Exception) {
                    error.value = "清除审批记录失败：" +
                        (failure.message ?: "设备存储异常")
                }
            }
        }
    }
}
