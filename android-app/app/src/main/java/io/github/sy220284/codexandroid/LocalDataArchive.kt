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
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/** Portable, on-device backup; never accesses a server or contacts a third party. */
internal object LocalDataArchive {
    private const val ARCHIVE_MARKER = "111-local-backup-version:4"
    private const val MEDIA_ARCHIVE_MARKER = "111-local-backup-version:3"
    private const val PREVIOUS_ARCHIVE_MARKER = "111-local-backup-version:2"
    private const val LEGACY_ARCHIVE_MARKER = "111-local-backup-version:1"
    private val PROJECTS_PREFIX = File("files", "projects").invariantSeparatorsPath
    private const val MAX_FILES = 15000
    private const val MAX_FILE_BYTES = 256L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 1024L * 1024 * 1024
    private const val READY = ".codex-restore-ready"
    private const val STAGED = ".codex-restore-staged"
    private const val INCOMING = ".codex-restore-incoming"
    private const val ROLLBACK = ".codex-restore-rollback"
    private const val RESULT_PREFS = "codex-data-transfer-status"
    private const val RESULT_KEY = "result"
    internal fun validatePath(name: String): Boolean = BackupDataPolicy.allows(name)

    // A pre-upgrade interrupted restore must be able to roll back exactly the
    // paths recorded in its journal; this is never used for a new import.
    private fun legacyRollbackPath(name: String): Boolean {
        if (!BackupDataPolicy.safeSyntax(name)) return false
        val parts = name.split('/')
        if (parts[1].startsWith(".codex-restore") || parts[1] == ".download-workspace-v1") return false
        if (parts[0] == "files" && (parts[1] in setOf("native-tools", "developer-runtime", "developer-packages", "developer-system-ca.pem") ||
                parts[1].startsWith("developer-system-ca.pem."))) return false
        if (parts[0] == "files" && parts[1] == "codex-home" &&
            parts.last().matches(Regex("(?i)(auth|credentials|tokens?)\\.json"))) return false
        return !(parts[0] == "shared_prefs" && (parts.last().contains("auth", ignoreCase = true) ||
            parts.last().contains("account", ignoreCase = true) || parts.last() == "$RESULT_PREFS.xml"))
    }

    fun lastResult(context: Context): String = context.getSharedPreferences(
        RESULT_PREFS, Context.MODE_PRIVATE).getString(RESULT_KEY, "").orEmpty()

    private fun saveResult(context: Context, message: String) {
        check(context.getSharedPreferences(RESULT_PREFS, Context.MODE_PRIVATE)
            .edit().putString(RESULT_KEY, message).commit()) { "恢复结果无法保存" }
    }

    private fun stagedFiles(root: File): List<Pair<String, File>> =
        Files.walk(root.toPath()).use { paths ->
            paths.map { path ->
                require(!Files.isSymbolicLink(path)) { "暂存备份包含符号链接" }
                path.toFile()
            }.filter { it.isFile && it != File(root, BackupIntegrity.STAGED_ENTRY) }
                .map { file -> file.relativeTo(root).invariantSeparatorsPath to file }
                .collect(java.util.stream.Collectors.toList())
        }.also { files -> require(files.size in 1..MAX_FILES && files.all { validatePath(it.first) }) {
            "暂存备份文件不属于允许迁移的数据"
        } }

    /** Streams the selected user data directly into the system document provider. */
    fun export(context: Context, output: OutputStream, workspaceRoot: File = WorkspaceStorage.root(context)): Int {
        val privateRoot = context.filesDir.canonicalFile
        BackupCheckpointValidation.verifyStore(File(privateRoot, AnswerCheckpointFormat.ROOT))
        PortableMediaArchive.verify(privateRoot, File(privateRoot, PortableMediaFormat.ATTACHMENTS))
        val prefsRoot = File(context.applicationInfo.dataDir, "shared_prefs").canonicalFile
        var count = 0
        var total = 0L
        val records = linkedMapOf<String, BackupIntegrity.Record>()
        ZipOutputStream(BufferedOutputStream(output)).use { archive ->
            archive.putNextEntry(ZipEntry("manifest.txt"))
            archive.write(ARCHIVE_MARKER.toByteArray(Charsets.UTF_8))
            archive.closeEntry()
            for ((prefix, root) in listOf("files" to privateRoot, PROJECTS_PREFIX to workspaceRoot.canonicalFile, "shared_prefs" to prefsRoot)) {
                if (!root.isDirectory) continue
                root.walkTopDown().onEnter { directory ->
                    val allowed = directory == root || (directory != WorkspaceStorage.legacyRoot(context) &&
                        BackupDataPolicy.mayContain("$prefix/" + directory.relativeTo(root).invariantSeparatorsPath))
                    if (allowed) require(!Files.isSymbolicLink(directory.toPath())) { "备份目录包含符号链接：" + directory.name }
                    allowed
                }.filter { it != root && (!it.isDirectory || Files.isSymbolicLink(it.toPath())) }.forEach { file ->
                    val name = "$prefix/" + file.relativeTo(root).invariantSeparatorsPath
                    if (!validatePath(name)) return@forEach
                    require(!Files.isSymbolicLink(file.toPath()) && file.isFile) { "备份包含不可移植的文件：" + file.name }
                    val canonical = file.canonicalFile
                    require(canonical.path.startsWith(root.path + File.separator)) { "备份文件路径越界：" + file.name }
                    if (++count > MAX_FILES) throw IOException("数据文件数量超过备份上限")
                    if (file.length() > MAX_FILE_BYTES ||
                        total + file.length() > MAX_TOTAL_BYTES) {
                        throw IOException("数据量超出 1 GiB 备份上限，请整理工作区后重试")
                    }
                    require(name.length <= 4096 && name.none(Char::isISOControl) && name !in records) { "备份路径无效或重复" }
                    val integrity = BackupIntegrity.Accumulator()
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
                            integrity.update(bytes, n)
                            archive.write(bytes, 0, n)
                        }
                    }
                    archive.closeEntry()
                    records[name] = integrity.finish()
                }
            }
            archive.putNextEntry(ZipEntry(BackupIntegrity.ENTRY))
            BackupIntegrity.write(archive, records)
            archive.closeEntry()
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
        var excluded = 0
        val records = linkedMapOf<String, BackupIntegrity.Record>()
        try {
            ZipInputStream(BufferedInputStream(input)).use { archive ->
                val marker = archive.nextEntry
                require(marker?.name == "manifest.txt") { "文件不是 111 本地备份" }
                val bytes = ByteArray(ARCHIVE_MARKER.length + 1)
                var size = 0
                while (size < bytes.size) {
                    val count = archive.read(bytes, size, bytes.size - size)
                    if (count < 0) break
                    size += count
                }
                val format = String(bytes, 0, size, Charsets.UTF_8)
                require(size == ARCHIVE_MARKER.length && format in setOf(ARCHIVE_MARKER, MEDIA_ARCHIVE_MARKER, PREVIOUS_ARCHIVE_MARKER, LEGACY_ARCHIVE_MARKER) &&
                    archive.read() == -1) { "备份格式或版本不受支持" }
                val legacy = format == LEGACY_ARCHIVE_MARKER
                val checksummed = format == ARCHIVE_MARKER
                var verified = false
                archive.closeEntry()
                while (true) {
                    val entry = archive.nextEntry ?: break
                    require(!verified) { "完整性清单必须是最后一个备份条目" }
                    if (checksummed && entry.name == BackupIntegrity.ENTRY && !entry.isDirectory) {
                        BackupIntegrity.verify(archive, records)
                        verified = true
                        archive.closeEntry()
                        continue
                    }
                    if (entry.isDirectory || !BackupDataPolicy.safeSyntax(entry.name) ||
                        !visited.add(entry.name)) {
                        throw IOException("备份包含无效或重复的文件路径：" + entry.name)
                    }
                    if (visited.size > MAX_FILES) throw IOException("备份文件数量超过上限")
                    val allowed = validatePath(entry.name)
                    if (!allowed && !legacy) throw IOException("备份包含不允许恢复的数据：" + entry.name)
                    val target = if (allowed) File(incoming, entry.name).canonicalFile else null
                    if (target != null) {
                        require(target.path.startsWith(incoming.canonicalPath + File.separator)) { "备份文件路径越界" }
                        if (target.parentFile?.isDirectory != true && target.parentFile?.mkdirs() != true)
                            throw IOException("无法创建备份文件目录")
                        count++
                    } else excluded++
                    val integrity = if (checksummed) BackupIntegrity.Accumulator() else null
                    target?.outputStream()?.buffered().use { out ->
                        val bytes = ByteArray(65536)
                        var fileSize = 0L
                        while (true) {
                            val n = archive.read(bytes)
                            if (n < 0) break
                            fileSize += n
                            total += n
                            if (fileSize > MAX_FILE_BYTES || total > MAX_TOTAL_BYTES)
                                throw IOException("备份内容超出安全大小上限")
                            integrity?.update(bytes, n)
                            out?.write(bytes, 0, n)
                        }
                    }
                    integrity?.let { records[entry.name] = it.finish() }
                    archive.closeEntry()
                }
                require(!checksummed || verified) { "备份缺少完整性清单" }
            }
            if (count == 0) throw IOException("备份中没有可恢复的数据")
            BackupCheckpointValidation.verify(incoming)
            PortableMediaArchive.relocate(File(incoming, "files"), context.filesDir)
            // Relocation legitimately changes some portable references. Seal the
            // resulting private staged files independently before publishing readiness.
            val stagedRecords = BackupIntegrity.snapshot(stagedFiles(incoming))
            File(incoming, BackupIntegrity.STAGED_ENTRY).outputStream().use {
                BackupIntegrity.write(it, stagedRecords)
            }
            val staged = File(context.filesDir, STAGED)
            if (staged.exists() && !staged.deleteRecursively())
                throw IOException("无法更换已有的恢复数据")
            if (!incoming.renameTo(staged)) throw IOException("无法保存经验证的恢复数据")
            val marker = File(context.filesDir, READY)
            if (!marker.writeText("pending-verified").let { marker.isFile }) {
                throw IOException("恢复标记创建失败")
            }
            saveResult(context, "备份已验证（$count 项）；" +
                (if (excluded > 0) "旧版备份中的 $excluded 项核心配置、运行状态或非迁移数据已排除；" else "") +
                "完全退出并重新打开应用后自动恢复。")
            return count
        } catch (failure: Exception) {
            incoming.deleteRecursively()
            throw failure
        }
    }

    fun hasPending(context: Context): Boolean = File(context.filesDir, READY).isFile

    /** Only cancel a staged import before any restore has begun in this process. */
    fun cancelStaged(context: Context) {
        val marker = File(context.filesDir, READY)
        check(marker.isFile && marker.readText() in setOf("pending", "pending-verified")) { "没有可取消的待恢复备份" }
        check(marker.delete()) { "无法取消恢复标记，请重试" }
        check(File(context.filesDir, STAGED).deleteRecursively()) { "恢复已取消，暂存目录清理失败" }
        saveResult(context, "已取消待恢复备份，当前数据保持不变。")
    }

    /** Called before creating a process-wide ViewModel or a native subprocess. */
    fun restoreIfPending(context: Context, workspaceRoot: () -> File = { WorkspaceStorage.root(context) }) {
        val marker = File(context.filesDir, READY)
        val transaction = LocalRestoreTransaction(File(context.filesDir, ROLLBACK))
        if (!marker.isFile) {
            // A marker-free journal can only be left by post-commit cleanup.
            if (transaction.hasJournal()) transaction.finish()
            return
        }
        val staged = File(context.filesDir, STAGED)
        var targets: List<Pair<File, File>>? = null
        var committed = false
        try {
            require(staged.isDirectory) { "已标记恢复但暂存数据缺失" }
            val files = Files.walk(staged.toPath()).use { paths ->
                paths.map { path ->
                    require(!Files.isSymbolicLink(path)) { "暂存备份包含符号链接" }
                    path.toFile()
                }.filter { it.isFile && it != File(staged, BackupIntegrity.STAGED_ENTRY) }.collect(java.util.stream.Collectors.toList())
            }
            require(files.isNotEmpty() && files.size <= MAX_FILES) { "恢复数据不完整" }
            val prefsRoot = File(context.applicationInfo.dataDir, "shared_prefs")
            val projectRoot by lazy { workspaceRoot().canonicalFile }
            val checkedTargets = files.sortedBy { it.relativeTo(staged).invariantSeparatorsPath }.map { file ->
                val name = file.relativeTo(staged).invariantSeparatorsPath
                require(validatePath(name) || (transaction.hasJournal() && legacyRollbackPath(name))) {
                    "恢复文件不属于允许迁移的数据，请重新选择备份：" + name
                }
                val target = if (name.startsWith("$PROJECTS_PREFIX/")) {
                    File(projectRoot, name.removePrefix("$PROJECTS_PREFIX/"))
                } else if (name.startsWith("files/")) {
                    require(name != PROJECTS_PREFIX) { "工作区必须是目录" }
                    File(context.filesDir, name.removePrefix("files/"))
                } else File(prefsRoot, name.removePrefix("shared_prefs/"))
                val targetRoot = when {
                    name.startsWith("$PROJECTS_PREFIX/") -> projectRoot
                    name.startsWith("files/") -> context.filesDir
                    else -> prefsRoot
                }
                val base = targetRoot.toPath().toAbsolutePath().normalize()
                val destination = target.toPath().toAbsolutePath().normalize()
                require(destination.startsWith(base) && destination != base) { "恢复路径越界" }
                var current = destination
                while (current != base) {
                    require(!Files.isSymbolicLink(current)) { "恢复路径包含符号链接：" + current.fileName }
                    if (current != destination && Files.exists(current))
                        require(Files.isDirectory(current)) { "恢复父目录与文件冲突：" + current.fileName }
                    current = current.parent
                }
                require(!Files.isSymbolicLink(base)) { "恢复根目录包含符号链接" }
                require(!target.exists() || target.isFile) { "恢复目标与目录冲突：" + target.name }
                file to target
            }
            targets = checkedTargets
            if (!transaction.hasJournal()) {
                val markerValue = marker.readText()
                require(markerValue in setOf("pending", "pending-verified")) { "未知恢复标记" }
                val seal = File(staged, BackupIntegrity.STAGED_ENTRY)
                if (markerValue == "pending-verified") {
                    require(seal.isFile && !Files.isSymbolicLink(seal.toPath())) { "暂存完整性清单缺失" }
                    seal.inputStream().buffered().use {
                        BackupIntegrity.verify(it, BackupIntegrity.snapshot(stagedFiles(staged)))
                    }
                }
                BackupCheckpointValidation.verify(staged)
                PortableMediaArchive.verify(File(staged, "files"), File(context.filesDir, PortableMediaFormat.ATTACHMENTS))
            }
            if (transaction.hasJournal()) {
                transaction.rollback(checkedTargets)
                check(marker.delete()) { "恢复回滚完成但标记未清理" }
                committed = true
                saveResult(context, "上次恢复被中断，已完整回滚原数据。可重新选择备份恢复。")
            } else {
                transaction.prepare(checkedTargets)
                transaction.apply(checkedTargets)
                check(marker.delete()) { "恢复提交标记无法清理" }
                committed = true
                saveResult(context, "本地数据恢复完成（${checkedTargets.size} 项）。账号登录凭据已排除，请重新登录。会话正文可由历史文件恢复；仅存于运行数据库的元数据不会随此格式迁移。")
            }
            transaction.finish()
            check(staged.deleteRecursively()) { "恢复暂存目录清理失败" }
        } catch (failure: Exception) {
            if (committed) {
                // Live data is already committed or rolled back; cleanup failure must
                // not cause the transaction to be replayed on another startup.
                saveResult(context, "恢复事务已结束，暂存清理或结果记录失败：" + failure.message.orEmpty())
                return
            }
            try {
                if (transaction.hasJournal()) {
                    transaction.rollback(requireNotNull(targets) { "恢复目标无法重新验证" })
                }
                check(marker.delete()) { "恢复标记无法清理" }
            } catch (rollback: Exception) {
                failure.addSuppressed(rollback)
                saveResult(context, "恢复回滚未完成，原数据快照仍保留，请检查存储：" + rollback.message.orEmpty())
                throw IOException("恢复回滚尚未完成，已阻止打开不一致的数据", failure)
            }
            saveResult(context, "恢复未完成，原数据已保留：" + failure.message.orEmpty())
            transaction.finish()
            // Keep the verified archive for diagnosis until the next explicit import.
        }
    }
}

internal data class DataTransferState(
    val busy: Boolean = false,
    val result: String = "",
    val pendingRestore: Boolean = false
)

/** Device-local operations survive leaving the settings page. */
internal object AppDataTransfer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val state = MutableStateFlow(DataTransferState())
    private val mutationCount = MutableStateFlow(0)
    val localMutations = mutationCount.asStateFlow()

    @Synchronized private fun tryBeginMutation(): Boolean {
        if (state.value.busy) return false
        mutationCount.value += 1
        return true
    }

    @Synchronized private fun endMutation() {
        check(mutationCount.value > 0)
        mutationCount.value -= 1
    }

    suspend fun <T> withLocalMutation(block: suspend () -> T): T {
        check(tryBeginMutation()) { "本机数据正在备份或恢复，请完成后再操作" }
        try { return block() }
        finally { endMutation() }
    }

    suspend fun <T> mutateLocal(block: () -> T): T = withLocalMutation {
        withContext(Dispatchers.IO) { block() }
    }

    suspend fun persistDrafts(block: () -> Unit) {
        while (!tryBeginMutation()) state.first { !it.busy }
        try { withContext(Dispatchers.IO) { block() } }
        finally { endMutation() }
    }

    @Synchronized private fun beginTransfer(message: String): Boolean {
        if (state.value.busy || mutationCount.value != 0) return false
        state.value = DataTransferState(busy = true, result = message)
        return true
    }

    fun export(context: Context, destination: Uri, drafts: Map<String, ComposerDraft>): Boolean {
        if (!beginTransfer("正在导出本机工作数据…")) return false
        scope.launch {
            state.value = try {
                DraftPersistence.save(context.applicationContext, drafts)
                val output = context.contentResolver.openOutputStream(destination)
                    ?: throw IOException("所选位置无法写入")
                val count = output.use { LocalDataArchive.export(context.applicationContext, it) }
                DataTransferState(result = "已导出 $count 项到所选文件。保管好备份，其中可能包含私有项目与聊天内容。")
            } catch (failure: Exception) {
                DataTransferState(result = "导出失败：" + (failure.message ?: "无法写入备份"))
            }
        }
        return true
    }

    fun import(context: Context, source: Uri): Boolean {
        if (!beginTransfer("正在校验本机备份…")) return false
        scope.launch {
            state.value = try {
                val input = context.contentResolver.openInputStream(source)
                    ?: throw IOException("所选备份文件无法打开")
                input.use { LocalDataArchive.stageImport(context.applicationContext, it) }
                DataTransferState(busy = true, pendingRestore = true, result = LocalDataArchive.lastResult(context))
            } catch (failure: Exception) {
                DataTransferState(busy = LocalDataArchive.hasPending(context), pendingRestore = LocalDataArchive.hasPending(context),
                    result = "导入检查未完成：" + (failure.message ?: "备份文件无效"))
            }
        }
        return true
    }

    @Synchronized fun cancelPending(context: Context) {
        if (!state.value.pendingRestore) return
        state.value = DataTransferState(busy = true, result = "正在取消待恢复备份")
        scope.launch {
            state.value = try {
                LocalDataArchive.cancelStaged(context.applicationContext)
                DataTransferState(result = "已取消待恢复备份")
            } catch (failure: Exception) {
                val pending = LocalDataArchive.hasPending(context)
                DataTransferState(busy = pending, pendingRestore = pending,
                    result = failure.message ?: "取消失败，请检查存储")
            }
        }
    }

}
