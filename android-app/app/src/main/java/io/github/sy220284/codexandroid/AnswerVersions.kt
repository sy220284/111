package io.github.sy220284.codexandroid

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Native IDs stay authoritative for execution. One logical chat owns its answer versions. */
internal data class AnswerVersion(val threadId: String, val turnId: String, val root: String, val slot: String)
internal data class AnswerVersionChoice(val index: Int, val versions: List<AnswerVersion>)
internal data class AnswerVersionState(
    val versions: List<AnswerVersion> = emptyList(), val selections: Map<String, String> = emptyMap()
) {
    fun root(id: String) = versions.firstOrNull { it.threadId == id }?.root ?: id
    fun members(id: String) = (listOf(root(id)) + versions.filter { it.root == root(id) }.map { it.threadId }).distinct()
    fun choice(thread: String, turn: String): AnswerVersionChoice? {
        val match = versions.firstOrNull { it.root == root(thread) && it.turnId == turn && turn.isNotBlank() } ?: return null
        val options = versions.filter { it.root == match.root && it.slot == match.slot }
        val index = options.indexOfFirst { it.turnId == turn }
        return if (options.size > 1 && index >= 0) AnswerVersionChoice(index, options) else null
    }
    fun add(source: String, turn: String, target: String): AnswerVersionState {
        require(source.isNotBlank() && turn.isNotBlank() && target.isNotBlank() && source != target)
        require(versions.none { it.threadId == target }) { "回答版本已存在" }
        val root = root(source)
        val existing = versions.firstOrNull { it.root == root && it.turnId == turn }
        val slot = existing?.slot ?: turn
        val first = if (existing == null) listOf(AnswerVersion(source, turn, root, slot)) else emptyList()
        return copy(versions = versions + first + AnswerVersion(target, "", root, slot),
            selections = selections + (root to (selections[root] ?: source)))
    }
    fun bind(thread: String, turn: String): AnswerVersionState =
        if (turn.isBlank()) this else copy(versions = versions.map { if (it.threadId == thread && it.turnId.isBlank()) it.copy(turnId = turn) else it })
    fun select(thread: String): AnswerVersionState =
        if (members(thread).size > 1) copy(selections = selections + (root(thread) to thread)) else this
    fun remove(thread: String): AnswerVersionState {
        val group = root(thread)
        val survivors = versions.filterNot { it.threadId == thread }
        val next = survivors.firstOrNull { it.root == group }?.threadId
        val mapped = if (thread == group && next != null) survivors.map { if (it.root == group) it.copy(root = next) else it } else survivors
        val retained = selections[group]?.takeUnless { it == thread } ?: next
        val key = if (thread == group) next else group
        return copy(versions = mapped, selections = if (key != null && retained != null) (selections - group) + (key to retained) else selections - group)
    }
    /** Keep the authoritative list intact; collapse only its presentation. */
    fun rows(rows: List<JSONObject>, matching: List<JSONObject> = rows): List<JSONObject> {
        val byId = rows.associateBy(SessionListPolicy::id)
        return matching.groupBy { root(SessionListPolicy.id(it)) }.map { (root, hits) ->
            val representative = hits.firstOrNull { SessionListPolicy.id(it) == selections[root] }
                ?: hits.firstOrNull { SessionListPolicy.id(it) == root } ?: hits.maxByOrNull(SessionListPolicy::recency)!!
            JSONObject(representative.toString()).apply {
                byId[root]?.optString("name")?.takeIf { it.isNotBlank() && it != "null" }?.let { put("name", it) }
            }
        }
    }
    fun encode(): String = JSONObject().put("versions", JSONArray().apply {
        versions.forEach { put(JSONObject().put("thread", it.threadId).put("turn", it.turnId)
            .put("root", it.root).put("slot", it.slot)) }
    }).put("selected", JSONObject(selections)).toString()
    companion object {
        fun decode(value: String): AnswerVersionState {
            val data = JSONObject(value)
            val rows = data.getJSONArray("versions")
            val selected = data.getJSONObject("selected")
            return AnswerVersionState((0 until rows.length()).map {
                val row = rows.getJSONObject(it)
                AnswerVersion(row.getString("thread"), row.getString("turn"), row.getString("root"), row.getString("slot"))
            }, selected.keys().asSequence().associateWith { selected.getString(it) })
        }
    }
}

internal class AnswerVersionStore(private val preferences: SharedPreferences) {
    val state = MutableStateFlow(preferences.getString("versions", null)?.let(AnswerVersionState::decode) ?: AnswerVersionState())
    private fun write(next: AnswerVersionState) {
        if (next == state.value) return
        check(preferences.edit().putString("versions", next.encode()).commit()) { "回答版本保存失败，请重试" }
        state.value = next
    }
    fun add(source: String, turn: String, target: String) = write(state.value.add(source, turn, target))
    fun bind(thread: String, turn: String) = write(state.value.bind(thread, turn))
    fun select(thread: String) = write(state.value.select(thread))
    fun remove(thread: String) = write(state.value.remove(thread))
}
