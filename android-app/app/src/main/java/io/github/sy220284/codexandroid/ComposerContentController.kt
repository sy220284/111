package io.github.sy220284.codexandroid

import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Capture the owner before launching an external picker; results never follow the visible page. */
internal class ComposerContentController(private val vm: CodexViewModel) {
    private data class Target(val thread: String?, val workspace: String)
    private var pendingDocument: Target? = null
    val documentOwner = MutableStateFlow<String?>(null)
    fun beginDocument(thread: String?, workspace: String): Boolean {
        if (pendingDocument != null || documentOwner.value != null || !vm.globalWorkSnapshot().canStartNewTask) return false
        pendingDocument = Target(thread, workspace)
        return true
    }
    fun importDocuments(uris: List<Uri>) {
        val target = pendingDocument ?: return
        pendingDocument = null
        if (uris.isEmpty()) return
        if (DataTransferMutationGate.refuse(vm, "composerDocuments", "本机附件导入")) return
        documentOwner.value = ThreadComposerDrafts.key(target.thread)
        vm.loading.value = vm.loading.value + "composerDocuments"
        vm.errors.value = vm.errors.value - "composerDocuments"
        vm.viewModelScope.launch {
            val failures = mutableListOf<String>()
            try {
                for (uri in uris) {
                    try {
                        val reference = withContext(Dispatchers.IO) {
                            val context = vm.getApplication<android.app.Application>()
                            val root = File(vm.workingDirectory)
                            val workspace = WorkspaceSelectionContract.validate(root, File(target.workspace))
                            val folder = WorkspaceFileActions.child(root, workspace, "任务附件")
                            if (!folder.exists()) check(folder.mkdir()) { "无法创建附件目录" }
                            val item = WorkspaceFileActions.createFolder(root, folder, UUID.randomUUID().toString())
                            try {
                                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
                                    ?: error("无法读取文件名称")
                                val input = context.contentResolver.openInputStream(uri) ?: error("无法打开文件")
                                input.use { WorkspaceImport.importFile(root, item, name, it) }
                                val file = WorkspaceFileActions.child(root, item, name)
                                ComposerReference("file", file.name, file.absolutePath)
                            } catch (failure: Exception) {
                                try { WorkspaceFileActions.delete(root, item) }
                                catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                                throw failure
                            }
                        }
                        addReference(target.thread, reference)
                    } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                    catch (failure: Exception) { failures += (failure.message ?: "文件导入失败") }
                }
                if (failures.isNotEmpty()) vm.errors.value = vm.errors.value + ("composerDocuments" to failures.joinToString("；"))
            } finally {
                vm.loading.value = vm.loading.value - "composerDocuments"
                documentOwner.value = null
            }
        }
    }
    fun addReference(thread: String?, reference: ComposerReference, trigger: String? = null) {
        vm.composerDrafts.update { drafts ->
            val original = drafts[ThreadComposerDrafts.key(thread)] ?: ComposerDraft()
            val updated = ThreadComposerDrafts.references(drafts, thread, original.references + reference)
            if (trigger != null && ComposerShortcutPolicy.referenceQuery(original.text) == trigger)
                ThreadComposerDrafts.text(updated, thread, original.text.dropLast(trigger.length + 1).trimEnd())
            else updated
        }
    }
    fun removeReference(thread: String?, reference: ComposerReference) {
        vm.composerDrafts.update { drafts ->
            val original = drafts[ThreadComposerDrafts.key(thread)] ?: ComposerDraft()
            ThreadComposerDrafts.references(drafts, thread, original.references - reference)
        }
    }
}
