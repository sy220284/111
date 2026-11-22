package io.github.sy220284.codexandroid

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/** Persist user text and portable image filenames, never absolute device paths. */
internal data class DraftRestoreResult(
    val drafts: Map<String, ComposerDraft>,
    val missingImages: Int = 0,
    val warning: String? = null
)

internal object DraftPersistence {
    private const val STORE = "codex-unsent-drafts"
    private const val KEY = "drafts"
    private val IMAGE_NAME = Regex(
        "^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}\\.(jpg|png|webp)$"
    )

    /** Accept pre-upgrade text-only records without migrating user content. */
    fun decode(raw: String): Map<String, ComposerDraft> {
        val source = JSONObject(raw)
        val result = linkedMapOf<String, ComposerDraft>()
        val keys = source.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val entry = source.getJSONObject(id)
            val names = entry.optJSONArray("images") ?: JSONArray()
            val saved = ComposerDraft(
                text = entry.optString("text"),
                images = (0 until names.length()).map { names.getString(it) }
            )
            if (saved.text.isNotBlank() || saved.images.isNotEmpty()) result[id] = saved
        }
        return result
    }

    fun encode(drafts: Map<String, ComposerDraft>): String {
        val source = JSONObject()
        drafts.forEach { (id, draft) ->
            val names = draft.images.map { absolute ->
                val name = File(absolute).name
                require(IMAGE_NAME.matches(name)) {
                    "未发送草稿包含未受管理的图片：$name"
                }
                name
            }.distinct()
            if (draft.text.isNotBlank() || names.isNotEmpty()) {
                source.put(id, JSONObject().put("text", draft.text)
                    .put("images", JSONArray(names)))
            }
        }
        return source.toString()
    }

    /** Restore only existing images inside our own durable attachment directory. */
    internal fun resolveStoredImages(
        directory: File, entries: Map<String, ComposerDraft>
    ): DraftRestoreResult {
        val folder = directory.canonicalFile
        var missing = 0
        val resolved = linkedMapOf<String, ComposerDraft>()
        for ((id, draft) in entries) {
            val files = mutableListOf<String>()
            for (name in draft.images) {
                if (!IMAGE_NAME.matches(name) || files.size >= LocalImageAttachments.MAX_FILES) {
                    missing++
                    continue
                }
                val file = File(folder, name).canonicalFile
                if (file.parentFile == folder && file.isFile && file.length() > 0L) {
                    if (file.path !in files) files += file.path
                } else missing++
            }
            val saved = draft.copy(images = files)
            if (saved.text.isNotBlank() || files.isNotEmpty()) resolved[id] = saved
        }
        return DraftRestoreResult(resolved, missing)
    }

    fun restore(context: Context): DraftRestoreResult {
        val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null) ?: return DraftRestoreResult(emptyMap())
        val decoded = try {
            decode(raw)
        } catch (failure: JSONException) {
            // Keep an untouched local copy before any new save can overwrite it.
            val backupKey = "drafts-recovery-" + System.currentTimeMillis()
            check(prefs.edit().putString(backupKey, raw).commit()) {
                "受损草稿无法备份，保留原始记录"
            }
            return DraftRestoreResult(emptyMap(), warning =
                "发现无法读取的旧草稿，原始记录已保存在本机备份 $backupKey；可通过本地数据导出保留")
        }
        return resolveStoredImages(LocalImageAttachments.directory(context), decoded)
    }

    fun save(context: Context, drafts: Map<String, ComposerDraft>) {
        check(context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .edit().putString(KEY, encode(drafts)).commit()) {
            "本机草稿保存失败，请检查存储空间"
        }
    }
}
