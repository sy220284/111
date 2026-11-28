package io.github.sy220284.codexandroid

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest

/** Shared handoff for immutable artifacts to a newly created SAF document. */
internal object VerifiedDocumentDelivery {
    private fun digest() = MessageDigest.getInstance("SHA-256")

    suspend fun transfer(size: Long, expectedHash: String?,
        source: () -> InputStream, write: () -> OutputStream, read: () -> InputStream,
        permit: () -> Unit, onWritten: suspend () -> Unit = {},
        onVerified: suspend (Long) -> Unit = {}) {
        val context = currentCoroutineContext()
        fun checkOwner() { context.ensureActive(); permit() }
        require(size >= 0)
        val buffer = ByteArray(64 * 1024)
        val original = digest()
        var copied = 0L
        checkOwner()
        source().use { input ->
            write().use { output ->
                while (true) {
                    checkOwner()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    copied += count
                    check(copied <= size) { "源文件大小已变化" }
                    checkOwner()
                    output.write(buffer, 0, count)
                    original.update(buffer, 0, count)
                }
                checkOwner()
                output.flush()
            }
        }
        val sourceHash = original.digest()
        check(copied == size) { "源文件读取不完整" }
        if (expectedHash != null)
            check(sourceHash.joinToString("") { "%02x".format(it.toInt() and 0xff) } == expectedHash) { "源文件内容已变化" }
        checkOwner()
        onWritten()
        val saved = digest()
        var verified = 0L
        var lastReport = 0L
        read().use { input ->
            while (true) {
                checkOwner()
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                verified += count
                check(verified <= size) { "保存结果大小不符" }
                saved.update(buffer, 0, count)
                if (verified - lastReport >= 256 * 1024 || verified == size) {
                    lastReport = verified
                    onVerified(verified)
                }
            }
        }
        checkOwner()
        check(verified == size && MessageDigest.isEqual(sourceHash, saved.digest())) { "保存后文件字节校验失败" }
    }

    // Only pass a URI returned by ACTION_CREATE_DOCUMENT, never an existing user file.
    fun discardNewDocument(resolver: ContentResolver, uri: Uri): Boolean =
        try { uri.scheme == "content" && DocumentsContract.deleteDocument(resolver, uri) }
        catch (failure: Exception) { android.util.Log.w("DocumentDelivery", "未完成的目标文件无法删除", failure); false }

    suspend fun saveNewDocument(resolver: ContentResolver, uri: Uri, file: File, size: Long,
        expectedHash: String? = null, permit: () -> Unit, beforeWrite: () -> Unit = {},
        onWritten: suspend () -> Unit = {}, onVerified: suspend (Long) -> Unit = {}) = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "请选择系统文档保存位置" }
        try {
            permit()
            beforeWrite()
            transfer(size, expectedHash,
                source = { Files.newInputStream(file.toPath(), NOFOLLOW_LINKS) },
                write = { requireNotNull(resolver.openOutputStream(uri, "wt")) { "无法打开保存位置" } },
                read = { requireNotNull(resolver.openInputStream(uri)) { "无法回读保存文件" } },
                permit = permit, onWritten = onWritten, onVerified = onVerified)
        } catch (failure: Exception) {
            val removed = discardNewDocument(resolver, uri)
            if (failure is CancellationException) throw failure
            val suffix = if (removed) "；未完成文件已清理" else "；所选位置可能留有未完成文件，请检查后重试"
            throw IOException((failure.message ?: "文件交付失败") + suffix, failure)
        }
    }
}
