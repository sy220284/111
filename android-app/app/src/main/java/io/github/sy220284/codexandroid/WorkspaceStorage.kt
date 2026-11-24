package io.github.sy220284.codexandroid

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Shared storage is the real workspace; account state and runtime remain private. */
internal object WorkspaceStorage {
    const val DISPLAY_ROOT = "Download/Codex"
    private const val MIGRATED = ".download-workspace-v1"

    fun authorized(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    fun legacyRoot(context: Context): File = File(context.filesDir, "projects").canonicalFile

    @Suppress("DEPRECATION")
    fun root(context: Context): File {
        check(authorized(context)) { "请先授权手机文件访问，以使用 Download/Codex 工作区" }
        check(Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) { "手机共享存储不可写" }
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).canonicalFile
        val root = File(downloads, "Codex").canonicalFile
        check(root.parentFile == downloads) { "工作区路径越界" }
        check(root.isDirectory || root.mkdirs()) { "无法创建 Download/Codex 工作区" }
        check(root.canRead() && root.canWrite()) { "Download/Codex 工作区无法读写" }
        return root
    }

    fun prepare(context: Context) {
        val target = root(context)
        val marker = File(context.filesDir, MIGRATED)
        if (!marker.isFile) {
            WorkspaceSelectionContract.migrate(legacyRoot(context), target)
            val stage = File.createTempFile(".workspace-migration-", ".tmp", context.filesDir)
            try {
                stage.writeText("Download/Codex")
                Files.move(stage.toPath(), marker.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } finally { Files.deleteIfExists(stage.toPath()) }
        }
    }

    fun remap(context: Context, path: String): String =
        WorkspaceSelectionContract.remap(legacyRoot(context), root(context), File(path)).absolutePath
}
