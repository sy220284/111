package io.github.sy220284.codexandroid

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** Use extracted APK-native executables while keeping app-owned data non-executable. */
object NativeToolInstaller {
    fun prepare(context: Context): File {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir).canonicalFile
        val tools = File(context.filesDir, "native-tools")
        val required = linkedMapOf(
            "rg" to "libcodex_rg.so", "git" to "libcodex_git.so",
            "curl" to "libcodex_curl.so", "python" to "libpython3.so",
            "python3" to "libpython3.so", "node" to "libnode.so", "npm" to "libnpm.so"
        )
        if (android.os.Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a") {
            required["git-remote-http"] = "libcodex_git_remote_http.so"
            required["git-remote-https"] = "libcodex_git_remote_http.so"
        }
        if (Files.isSymbolicLink(tools.toPath()) || (tools.exists() && (!tools.isDirectory ||
                tools.list()?.any { it !in required } != false))) RuntimePayloadIntegrity.removeTree(tools)
        check(tools.isDirectory || tools.mkdirs()) { "无法建立本机工具目录" }
        required.forEach { (name, library) ->
            val executable = File(nativeDir, library)
            require(executable.isFile) {
                "APK 缺少当前设备架构的本地开发工具 $name，请安装完整运行包"
            }
            val link = File(tools, name).toPath()
            val target = executable.toPath()
            if (Files.isSymbolicLink(link)) {
                if (Files.readSymbolicLink(link) == target) return@forEach
                Files.delete(link)
            } else {
                require(!Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
                    "本机 $name 命令入口存在异常文件：$link"
                }
            }
            Files.createSymbolicLink(link, target)
            require(Files.isSymbolicLink(link) && Files.readSymbolicLink(link) == target) {
                "无法建立本机 $name 命令入口"
            }
        }
        return tools
    }
}
