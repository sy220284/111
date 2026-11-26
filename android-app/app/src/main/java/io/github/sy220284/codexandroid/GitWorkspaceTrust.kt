package io.github.sy220284.codexandroid

import java.io.File

/** Android shared storage reports a different filesystem owner. Trust only the user's workspace tree. */
internal object GitWorkspaceTrust {
    fun configure(workspace: File, environment: MutableMap<String, String>) {
        val root = workspace.canonicalFile
        require(root.isDirectory && root.parentFile != null) { "Git 工作区未就绪" }
        val count = environment["GIT_CONFIG_COUNT"]?.toIntOrNull() ?: 0
        require(count in 0..128 && (environment["GIT_CONFIG_COUNT"] == null || environment["GIT_CONFIG_COUNT"]?.toIntOrNull() != null)) {
            "Git 进程配置数量无效"
        }
        for ((offset, path) in listOf(root.path, root.path + "/*").withIndex()) {
            environment["GIT_CONFIG_KEY_${count + offset}"] = "safe.directory"
            environment["GIT_CONFIG_VALUE_${count + offset}"] = path
        }
        environment["GIT_CONFIG_COUNT"] = (count + 2).toString()
    }
}
