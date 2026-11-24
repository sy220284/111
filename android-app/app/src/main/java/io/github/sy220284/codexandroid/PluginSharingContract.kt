package io.github.sy220284.codexandroid

import org.json.JSONArray
import org.json.JSONObject

internal object PluginSharingContract {
    private val idPattern = Regex("[A-Za-z0-9_~-]+")
    private const val ID_LABEL = "插件标识："

    fun remoteId(value: String): String {
        val candidate = value.lineSequence().firstOrNull { it.trimStart().startsWith(ID_LABEL) }
            ?.trim()?.removePrefix(ID_LABEL)?.trim() ?: value.trim()
        require(idPattern.matches(candidate)) { "请粘贴共享信息或填写插件标识" }
        return candidate
    }

    fun shareDetails(id: String, url: String): String =
        listOf(ID_LABEL + remoteId(id), url.trim()).filter(String::isNotBlank).joinToString("\n")

    fun targets(value: String): JSONArray {
        val entries = value.lineSequence().map(String::trim).filter(String::isNotBlank).map { line ->
            val fields = line.split(':').map(String::trim)
            require(fields.size == 3 && fields[0] in setOf("user", "group") &&
                fields[1].isNotBlank() && fields[2] in setOf("reader", "editor")) {
                "每行格式为 user:账号标识:reader 或 group:群组标识:editor"
            }
            Triple(fields[0], fields[1], fields[2])
        }.toList()
        require(entries.distinctBy { it.first to it.second }.size == entries.size) { "共享对象有重复，请保留一个权限" }
        return JSONArray().apply {
            entries.forEach { (type, id, role) ->
                put(JSONObject().put("principalType", type).put("principalId", id).put("role", role))
            }
        }
    }

    fun localSource(plugin: JSONObject): String =
        plugin.optJSONObject("source")?.takeIf { it.value("type") == "local" }?.value("path").orEmpty()
}
