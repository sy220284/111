package io.github.sy220284.codexandroid

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** Use extracted APK-native executables while keeping app-owned data non-executable. */
object NativeToolInstaller {
    @Synchronized fun prepare(context: Context): File {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir).canonicalFile
        val tools = File(context.filesDir, "native-tools")
        val commands = linkedMapOf<String, String>()
        context.assets.open("development-runtime/development-runtime.commands.tsv").bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val parts = line.split('\t')
                require(parts.size == 2 && parts[0].matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._+-]*")) &&
                    parts[1].matches(Regex("libcodex_[a-zA-Z0-9_]+\\.so"))) { "开发工具入口清单非法" }
                require(commands.put(parts[0], parts[1]) == null) { "开发工具入口重复" }
            }
        }
        require(commands.keys.containsAll(listOf("git", "git-remote-http", "git-remote-https", "rg", "jq",
            "curl", "python", "python3", "pip", "pip3", "node", "npm"))) { "APK 开发工具清单不完整" }
        commands.values.distinct().forEach { library ->
            require(File(nativeDir, library).isFile) { "APK 缺少开发工具：" + library }
        }
        val required = commands.keys.associateWith { "libcodex_tool_launcher.so" }
        require(File(nativeDir, "libcodex_tool_launcher.so").isFile) { "APK 缺少开发工具启动器" }
        val invalid = Files.isSymbolicLink(tools.toPath()) || (tools.exists() &&
            (!tools.isDirectory || tools.list()?.any { it !in required } != false || required.any { (name, library) ->
                val link = File(tools, name).toPath()
                Files.exists(link, LinkOption.NOFOLLOW_LINKS) &&
                    (!Files.isSymbolicLink(link) || Files.readSymbolicLink(link) != File(nativeDir, library).toPath())
            }))
        if (invalid) RuntimePayloadIntegrity.removeTree(tools)
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
