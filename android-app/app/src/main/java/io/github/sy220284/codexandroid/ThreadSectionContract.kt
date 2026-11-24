package io.github.sy220284.codexandroid

import org.json.JSONObject

/**
 * Server-backed, user-visible thread sections.
 *
 * Empty section selection means all sections; UNGROUPED intentionally sends
 * JSON null, which is distinct from omitting sectionId on thread/list.
 */
object ThreadSectionContract {
    const val PINNED = "01984de2-8f74-7c91-a3b2-5c5e937cf318"
    const val UNGROUPED = "__unsectioned__"

    private fun requiredId(value: String): String =
        value.trim().also { require(it.isNotEmpty()) { "请选择有效对象" } }

    private fun checkedName(value: String): String =
        value.trim().also { require(it.isNotEmpty() && it.length <= 120) {
            "分组名称须为 1–120 个字符"
        } }

    fun threadList(archived: Boolean, term: String, cursor: String?, section: String): JSONObject =
        JSONObject().put("limit", 35).put("archived", archived).apply {
            cursor?.takeIf { it.isNotBlank() }?.let { put("cursor", it) }
            term.trim().takeIf { it.isNotEmpty() }?.let { put("searchTerm", it) }
            when (section) {
                UNGROUPED -> put("sectionId", JSONObject.NULL)
                "" -> Unit
                else -> put("sectionId", requiredId(section))
            }
        }

    fun create(name: String): JSONObject = JSONObject().put("name", checkedName(name))

    fun rename(sectionId: String, name: String): JSONObject = JSONObject()
        .put("sectionId", requiredId(sectionId)).put("name", checkedName(name))

    fun delete(sectionId: String): JSONObject = JSONObject()
        .put("sectionId", requiredId(sectionId))

    fun move(threadId: String, sectionId: String?): JSONObject = JSONObject()
        .put("threadId", requiredId(threadId))
        .put("sectionId", sectionId?.let(::requiredId) ?: JSONObject.NULL)
}
