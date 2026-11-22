package io.github.sy220284.codexandroid

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** Use extracted APK-native executables while keeping app-owned data non-executable. */
object NativeToolInstaller {
    fun prepare(context: Context): File {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir).canonicalFile
        val ripgrep = File(nativeDir, "libcodex_rg.so")
        require(ripgrep.isFile) { "APK 缺少当前设备架构的文件搜索工具 rg，请安装完整运行包" }
        val tools = File(context.filesDir, "native-tools").apply { mkdirs() }
        val link = File(tools, "rg").toPath()
        val target = ripgrep.toPath()
        if (Files.isSymbolicLink(link)) {
            if (Files.readSymbolicLink(link) == target) return tools
            Files.delete(link)
        } else {
            require(!Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
                "文件搜索工具入口存在异常文件：$link"
            }
        }
        Files.createSymbolicLink(link, target)
        require(Files.isSymbolicLink(link) && Files.readSymbolicLink(link) == target) {
            "无法创建本机文件搜索工具入口"
        }
        return tools
    }
}
