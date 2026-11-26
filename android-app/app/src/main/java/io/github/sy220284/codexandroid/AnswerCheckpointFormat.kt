package io.github.sy220284.codexandroid

import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal object AnswerCheckpointFormat {
    const val ROOT = "answer-checkpoints"
    const val OBJECTS = "objects"
    const val MANIFESTS = "manifests"
    private val idPattern = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
    private val digestPattern = Regex("[0-9a-f]{64}")

    fun manifestName(id: String): String = UUID.fromString(id).toString() + ".json"
    fun isManifestName(name: String): Boolean =
        name.endsWith(".json") && idPattern.matches(name.removeSuffix(".json"))
    fun isObjectName(name: String): Boolean = digestPattern.matches(name)
    fun digest(file: File): String {
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                sha.update(buffer, 0, size)
            }
        }
        return sha.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
