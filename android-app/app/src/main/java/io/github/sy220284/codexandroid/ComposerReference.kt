package io.github.sy220284.codexandroid

import java.io.File
import org.json.JSONObject

/** Explicitly selected local files and installed skills; no invented RPC attachment variant. */
data class ComposerReference(val kind: String, val name: String, val path: String) {
    init {
        require(kind in setOf("file", "skill") && name.isNotBlank() && path.isNotBlank()) { "引用内容无效" }
    }
    fun encode() = JSONObject().put("kind", kind).put("name", name).put("path", path)
    companion object {
        fun decode(value: JSONObject) = ComposerReference(
            value.getString("kind"), value.getString("name"), value.getString("path"))
    }
}

internal object ComposerInputPolicy {
    fun validate(images: List<String>, references: List<ComposerReference>, imageRoot: File, workspaceRoot: File) {
        val root = workspaceRoot.canonicalFile
        val folder = imageRoot.canonicalFile
        require(images.size <= LocalImageAttachments.MAX_FILES) { "图片数量超限" }
        images.forEach {
            val file = File(it).canonicalFile
            require(file.parentFile == folder && file.isFile && file.length() > 0L) { "图片附件已失效，请重新添加" }
        }
        references.forEach {
            val file = File(it.path).canonicalFile
            require(file.isFile && File(it.path).isAbsolute) { "引用已失效：${it.name}" }
            if (it.kind == "file") require(file.path.startsWith(root.path + File.separator)) { "引用文件不在本机工作区" }
        }
    }
}
