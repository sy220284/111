package io.github.sy220284.codexandroid

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Portable, on-device backup; never accesses a server or contacts a third party. */
internal object LocalDataArchive {
    private const val ARCHIVE_MARKER = "111-local-backup-version:1"
    private const val MAX_FILES = 15000
    private const val MAX_FILE_BYTES = 256L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 1024L * 1024 * 1024
    private const val READY = ".codex-restore-ready"
    private const val STAGED = ".codex-restore-staged"
    private const val INCOMING = ".codex-restore-incoming"
    private const val RESULT_PREFS = "codex-data-transfer-status"
    private const val RESULT_KEY = "result"

    internal fun validatePath(name: String): Boolean {
        if (!name.startsWith("files/") && !name.startsWith("shared_prefs/")) return false
        if (name.contains('\\') || name.startsWith('/') || name.endsWith('/')) return false
        val parts = name.split('/')
        if (parts.size < 2 || parts.any { it.isBlank() || it == "." || it == ".." }) return false
        if (parts[1].startsWith(".codex-restore")) return false
        if (parts[0] == "files" && parts[1] == "codex-home" &&
            parts.last().matches(Regex("(?i)(auth|credentials|tokens?)\\.json"))) return false
        if (parts[0] == "shared_prefs" &&
            (parts.last().contains("auth", ignoreCase = true) ||
                parts.last().contains("account", ignoreCase = true) ||
                parts.last() == "$RESULT_PREFS.xml")) return false
        return true
    }

    fun lastResult(context: Context): String = context.getSharedPreferences(
        RESULT_PREFS, Context.MODE_PRIVATE).getString(RESULT_KEY, "").orEmpty()

    private fun saveResult(context: Context, message: String) {
        check(context.getSharedPreferences(RESULT_PREFS, Context.MODE_PRIVATE)
            .edit().putString(RESULT_KEY, message).commit()) { "恢复结果无法保存" }
    }

    /** Streams the selected user data directly into the system document provider. */
    fun export(context: Context, output: OutputStream): Int {
        val privateRoot = context.filesDir.canonicalFile
        val prefsRoot = File(context.applicationInfo.dataDir, "shared_prefs").canonicalFile
        var count = 0
        var total = 0L
        ZipOutputStream(BufferedOutputStream(output)).use { archive ->
            archive.putNextEntry(ZipEntry("manifest.txt"))
            archive.write(ARCHIVE_MARKER.toByteArray(Charsets.UTF_8))
            archive.closeEntry()
            for ((prefix, root) in listOf("files" to privateRoot, "shared_prefs" to prefsRoot)) {
                if (!root.isDirectory) continue
                root.walkTopDown().onEnter { directory ->
                    !directory.name.startsWith(".codex-restore")
                }.filter { it.isFile }.forEach { file ->
                    val canonical = file.canonicalFile
                    if (!canonical.path.startsWith(root.path + File.separator)) {
                        throw IOException("发现超出私有目录的文件：" + file.name)
                    }
                    val name = "$prefix/" + canonical.relativeTo(root).invariantSeparatorsPath
                    if (!validatePath(name)) return@forEach
                    if (++count > MAX_FILES) throw IOException("数据文件数量超过备份上限")
                    if (file.length() > MAX_FILE_BYTES ||
                        total + file.length() > MAX_TOTAL_BYTES) {
                        throw IOException("数据量超出 1 GiB 备份上限，请整理工作区后重试")
                    }
                    archive.putNextEntry(ZipEntry(name))
                    file.inputStream().buffered().use { input ->
                        val bytes = ByteArray(65536)
                        var fileSize = 0L
                        while (true) {
                            val n = input.read(bytes)
                            if (n < 0) break
                            fileSize += n
                            total += n
                            if (fileSize > MAX_FILE_BYTES || total > MAX_TOTAL_BYTES)
                                throw IOException("文件在导出期间超过备份上限")
                            archive.write(bytes, 0, n)
                        }
                    }
                    archive.closeEntry()
                }
            }
        }
        return count
    }

    /** Verify, bound and stage a ZIP without touching any running Codex data. */
    fun stageImport(context: Context, input: InputStream): Int {
        val incoming = File(context.filesDir, INCOMING)
        if (incoming.exists() && !incoming.deleteRecursively())
            throw IOException("无法移除上一次中断的暂存数据")
        if (!incoming.mkdirs()) throw IOException("无法创建恢复暂存目录")
        var count = 0
        var total = 0L
        val visited = HashSet<String>()
        try {
            ZipInputStream(BufferedInputStream(input)).use { archive ->
                val marker = archive.nextEntry
                require(marker?.name == "manifest.txt") { "文件不是 111 本地备份" }
                val bytes = ByteArray(ARCHIVE_MARKER.length + 1)
                val size = archive.read(bytes)
                require(size == ARCHIVE_MARKER.length &&
                    String(bytes, 0, size, Charsets.UTF_8) == ARCHIVE_MARKER &&
                    archive.read() == -1) { "备份格式或版本不受支持" }
                archive.closeEntry()
                while (true) {
                    val entry = archive.nextEntry ?: break
                    if (entry.isDirectory || !validatePath(entry.name) ||
                        !visited.add(entry.name)) {
                        throw IOException("备份包含无效或重复的文件路径：" + entry.name)
                    }
                    if (++count > MAX_FILES) throw IOException("备份文件数量超过上限")
                    val target = File(incoming, entry.name).canonicalFile
                    require(target.path.startsWith(incoming.canonicalPath + File.separator)) {
                        "备份文件路径越界"
                    }
                    if (target.parentFile?.isDirectory != true && target.parentFile?.mkdirs() != true)
                        throw IOException("无法创建备份文件目录")
                    target.outputStream().buffered().use { out ->
                        val bytes = ByteArray(65536)
                        var fileSize = 0L
                        while (true) {
                            val n = archive.read(bytes)
                            if (n < 0) break
                            fileSize += n
                            total += n
                            if (fileSize > MAX_FILE_BYTES || total > MAX_TOTAL_BYTES)
                                throw IOException("备份内容超出安全大小上限")
                            out.write(bytes, 0, n)
                        }
                    }
                    archive.closeEntry()
                }
            }
            if (count == 0) throw IOException("备份中没有可恢复的数据")
            val staged = File(context.filesDir, STAGED)
            if (staged.exists() && !staged.deleteRecursively())
                throw IOException("无法更换已有的恢复数据")
            if (!incoming.renameTo(staged)) throw IOException("无法保存经验证的恢复数据")
            val marker = File(context.filesDir, READY)
            if (!marker.writeText("pending").let { marker.isFile }) {
                throw IOException("恢复标记创建失败")
            }
            saveResult(context, "备份已验证（$count 项）；完全退出并重新打开应用后自动恢复。")
            return count
        } catch (failure: Exception) {
            incoming.deleteRecursively()
            throw failure
        }
    }

    /** Called before creating a process-wide ViewModel or a native subprocess. */
    fun restoreIfPending(context: Context) {
        val marker = File(context.filesDir, READY)
        if (!marker.isFile) return
        val staged = File(context.filesDir, STAGED)
        try {
            require(staged.isDirectory) { "已标记恢复但暂存数据缺失" }
            val files = staged.walkTopDown().filter { it.isFile }.toList()
            require(files.isNotEmpty() && files.size <= MAX_FILES) { "恢复数据不完整" }
            val prefsRoot = File(context.applicationInfo.dataDir, "shared_prefs")
            var recovered = 0
            for (file in files) {
                val name = file.relativeTo(staged).invariantSeparatorsPath
                require(validatePath(name)) { "恢复文件路径无效：" + name }
                val target = if (name.startsWith("files/")) {
                    File(context.filesDir, name.removePrefix("files/"))
                } else File(prefsRoot, name.removePrefix("shared_prefs/"))
                if (target.parentFile?.isDirectory != true && target.parentFile?.mkdirs() != true)
                    throw IOException("恢复目标目录无法创建")
                Files.move(file.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                recovered++
            }
            if (!marker.delete()) throw IOException("恢复完成但标记未清理")
            if (!staged.deleteRecursively()) throw IOException("无法清理恢复暂存目录")
            saveResult(context, "本地数据恢复完成（$recovered 项）。账号登录凭据已排除，请重新登录。")
        } catch (failure: Exception) {
            saveResult(context, "恢复未完成：" + (failure.message ?: "请检查本机存储空间") +
                "。暂存数据已保留，下次完整启动将继续恢复。")
        }
    }
}

internal data class DataTransferState(
    val busy: Boolean = false,
    val result: String = ""
)

/** Device-local operations survive leaving the settings page. */
internal object AppDataTransfer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val state = MutableStateFlow(DataTransferState())

    fun export(context: Context, destination: Uri) {
        if (state.value.busy) return
        state.value = DataTransferState(busy = true, result = "正在导出本机工作数据…")
        scope.launch {
            state.value = try {
                val output = context.contentResolver.openOutputStream(destination)
                    ?: throw IOException("所选位置无法写入")
                val count = output.use { LocalDataArchive.export(context.applicationContext, it) }
                DataTransferState(result = "已导出 $count 项到所选文件。保管好备份，其中可能包含私有项目与聊天内容。")
            } catch (failure: Exception) {
                DataTransferState(result = "导出失败：" + (failure.message ?: "无法写入备份"))
            }
        }
    }

    fun import(context: Context, source: Uri) {
        if (state.value.busy) return
        state.value = DataTransferState(busy = true, result = "正在校验本机备份…")
        scope.launch {
            state.value = try {
                val input = context.contentResolver.openInputStream(source)
                    ?: throw IOException("所选备份文件无法打开")
                val count = input.use { LocalDataArchive.stageImport(context.applicationContext, it) }
                DataTransferState(result = "已验证 $count 项。请完全关闭应用并重新启动以恢复数据。")
            } catch (failure: Exception) {
                DataTransferState(result = "导入失败：" + (failure.message ?: "备份文件无效"))
            }
        }
    }
}
