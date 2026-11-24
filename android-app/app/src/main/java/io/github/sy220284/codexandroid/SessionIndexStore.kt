package io.github.sy220284.codexandroid

import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Private, account-scoped local display index. Native thread/list remains authoritative. */
internal class SessionIndexStore(file: File) {
    private val atomic = AtomicFile(file)
    private val lock = Mutex()
    suspend fun read(identity: String): JSONObject? = withContext(Dispatchers.IO) {
        lock.withLock {
            try {
                val snapshot = JSONObject(atomic.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
                if (snapshot.optString("identity") == identity) snapshot.getJSONObject("active") else null
            } catch (_: FileNotFoundException) { null }
            catch (failure: Exception) {
                Log.w("SessionIndex", "本机会话索引损坏，将从内核重新建立", failure)
                atomic.delete()
                null
            }
        }
    }
    suspend fun write(identity: String, active: JSONObject) = withContext(Dispatchers.IO) {
        lock.withLock {
            val stream = atomic.startWrite()
            try {
                stream.write(JSONObject().put("identity", identity).put("active", active).toString().toByteArray(Charsets.UTF_8))
                atomic.finishWrite(stream)
            } catch (failure: Exception) {
                atomic.failWrite(stream)
                throw failure
            }
        }
    }
    companion object {
        fun identity(account: JSONObject): String {
            val source = account.opt("account").toString() + ":" + account.optBoolean("requiresOpenaiAuth")
            return MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}
