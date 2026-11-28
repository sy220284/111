package io.github.sy220284.codexandroid

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Local private index; native thread/list is the authoritative source.
 * SQLite updates only changed IDs and sorts by the same native recency key. */
internal class SessionIndexStore(private val file: File) {
    private val lock = Mutex()
    private fun open(): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(file, null).apply {
        execSQL("CREATE TABLE IF NOT EXISTS sessions (scope TEXT NOT NULL, id TEXT NOT NULL, recency INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(scope, id))")
        execSQL("CREATE INDEX IF NOT EXISTS sessions_recency ON sessions(scope, recency DESC, id DESC)")
    }
    suspend fun read(identity: String, limit: Int? = 100): JSONObject? = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!file.exists()) return@withLock null
            try {
                open().use { db ->
                    val rows = JSONArray()
                    db.query("sessions", arrayOf("payload"), "scope = ?", arrayOf(identity),
                        null, null, "recency DESC, id DESC", limit?.toString()).use { cursor ->
                        while (cursor.moveToNext()) rows.put(JSONObject(cursor.getString(0)))
                    }
                    if (rows.length() == 0) null else JSONObject().put("data", rows)
                }
            } catch (failure: Exception) {
                Log.w("SessionIndex", "本机会话索引损坏，将从内核重新建立", failure)
                SQLiteDatabase.deleteDatabase(file)
                null
            }
        }
    }
    suspend fun write(identity: String, active: JSONObject, complete: Boolean, changed: Set<String>) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                open().use { db ->
                    db.beginTransaction()
                    try {
                        if (complete) db.delete("sessions", "scope = ?", arrayOf(identity))
                        else for (id in changed) db.delete("sessions", "scope = ? AND id = ?", arrayOf(identity, id))
                        for (row in records(active)) {
                            val id = SessionListPolicy.id(row)
                            if (id.isBlank()) continue
                            val values = ContentValues().apply {
                                put("scope", identity); put("id", id)
                                put("recency", SessionListPolicy.recency(row)); put("payload", row.toString())
                            }
                            db.insertWithOnConflict("sessions", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                                .also { check(it != -1L) { "会话索引写入失败" } }
                        }
                        db.setTransactionSuccessful()
                    } finally { db.endTransaction() }
                }
            }
        }
    suspend fun clear() = withContext(Dispatchers.IO) {
        lock.withLock { if (file.exists()) SQLiteDatabase.deleteDatabase(file) }
    }
}
