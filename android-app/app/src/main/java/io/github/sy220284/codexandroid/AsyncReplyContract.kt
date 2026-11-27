package io.github.sy220284.codexandroid

import org.json.JSONArray
import org.json.JSONObject

internal data class AsyncReplyQuestion(val id: String, val messageId: String, val turnId: String,
    val title: String, val options: List<String>)

/** Same stable ID and reply envelope as locked Codex AnsweredQuestion. */
internal object AsyncReplyContract {
    private const val OPEN = "<send_user_message_question_reply>"
    private const val CLOSE = "</send_user_message_question_reply>"
    private fun utf8Prefix(value: String, limit: Int): String {
        val result = StringBuilder()
        var index = 0
        var bytes = 0
        while (index < value.length) {
            val code = value.codePointAt(index)
            val part = String(Character.toChars(code))
            val length = part.toByteArray(Charsets.UTF_8).size
            if (bytes + length > limit) break
            result.append(part); bytes += length; index += Character.charCount(code)
        }
        return result.toString()
    }
    fun attributes(item: TimelineItem): List<JSONObject> {
        if (item.kind !in setOf("agent", "agentMessage")) return emptyList()
        val raw = item.metadata() ?: return emptyList()
        val fragments = raw.optJSONArray("fragments")
        return if (fragments == null) listOf(raw)
        else (0 until fragments.length()).mapNotNull { fragments.optJSONObject(it) }
    }
    fun questions(item: TimelineItem): List<AsyncReplyQuestion> = attributes(item).flatMap { raw ->
        if (AgentReplyContract.restricted(raw)) return@flatMap emptyList()
        val message = raw.optString("id").ifBlank { item.id }
        val questions = raw.optJSONArray("questions") ?: return@flatMap emptyList()
        (0 until minOf(questions.length(), 64)).mapNotNull { i ->
            val q = questions.optJSONObject(i) ?: return@mapNotNull null
            val title = q.optString("title").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val id = JSONArray().put("request_user_input_async").put(message).put(i).toString()
            if (id.toByteArray(Charsets.UTF_8).size > 512) return@mapNotNull null
            val options = q.optJSONArray("options")
            AsyncReplyQuestion(id, message, item.turnId, title, if (options == null) emptyList()
                else (0 until minOf(options.length(), 32)).mapNotNull { index ->
                    (options.opt(index) as? String)?.takeIf { it.isNotBlank() &&
                        it.toByteArray(Charsets.UTF_8).size <= 512 }
                }.distinct())
        }
    }
    fun key(thread: String, question: AsyncReplyQuestion) = "__async_question__:" + thread + ":" + question.id
    fun receiptKey(thread: String, question: AsyncReplyQuestion) = key(thread, question) + ":receipt"
    fun encode(question: AsyncReplyQuestion, answer: String): String {
        require(answer.isNotBlank() && answer.length <= 16384)
        val title = utf8Prefix(question.title, 512).replace('\n', ' ').replace('\r', ' ')
        val body = JSONArray().put(JSONObject().put("answer", answer.trim()).put("question", title)
            .put("questionItemId", question.id))
        return "$OPEN\n$body\n$CLOSE"
    }
    private fun replies(value: String): List<JSONObject> {
        val text = value.trim()
        if (!text.startsWith(OPEN) || !text.endsWith(CLOSE)) return emptyList()
        val raw = try { JSONArray(text.removePrefix(OPEN).removeSuffix(CLOSE).trim()) }
            catch (_: org.json.JSONException) { return emptyList() }
        if (raw.length() !in 1..64) return emptyList()
        return (0 until raw.length()).mapNotNull { i -> raw.optJSONObject(i)?.takeIf {
            it.opt("questionItemId") is String && it.opt("question") is String && it.opt("answer") is String &&
                it.optString("questionItemId").isNotBlank() && it.optString("answer").isNotBlank()
        } }.takeIf { it.size == raw.length() }.orEmpty()
    }
    fun answeredIds(value: String): Set<String> = replies(value).map { it.getString("questionItemId") }.toSet()
    fun visibleUserText(value: String): String {
        val records = replies(value)
        return if (records.isEmpty()) value else records.joinToString("\n\n") {
            "回答：" + it.getString("question") + "\n" + it.getString("answer")
        }
    }
    fun answeredFromInput(input: JSONArray?): Set<String> =
        if (input == null) emptySet() else (0 until input.length()).flatMap { i ->
            val part = input.optJSONObject(i)
            if (part?.optString("type") == "text") answeredIds(part.optString("text")) else emptySet()
        }.toSet()
    fun answeredFromTimeline(items: List<TimelineItem>): Set<String> = items.filter { it.kind == "user" }
        .flatMap { answeredFromInput(it.metadata()?.optJSONArray("content")) }.toSet()
    fun answeredFromQueue(payload: JSONObject?): Set<String> {
        val data = payload?.optJSONArray("data") ?: return emptySet()
        return (0 until data.length()).flatMap { answeredFromInput(data.optJSONObject(it)?.optJSONArray("input")) }.toSet()
    }
}
