package io.github.sy220284.codexandroid

import android.net.Uri
import android.content.ClipboardManager
import android.content.Intent
import android.provider.MediaStore
import android.speech.RecognizerIntent
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.first
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
    private var cameraFile: File? = null
    private var cameraUri: Uri? = null
    private var voiceOwner: Target? = null
    private val context get() = vm.getApplication<android.app.Application>()
    fun canTakePhoto() = Intent(MediaStore.ACTION_IMAGE_CAPTURE).resolveActivity(context.packageManager) != null
    fun canRecognizeSpeech() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).resolveActivity(context.packageManager) != null
    fun hasClipboardImage() =
        context.getSystemService(ClipboardManager::class.java)?.primaryClipDescription?.hasMimeType("image/*") == true
    private fun report(message: String) {
        vm.errors.value = vm.errors.value + ("composerDocuments" to message)
    }
    fun prepareCamera(thread: String?): Uri? {
        if (!vm.globalWorkSnapshot().canStartNewTask || cameraFile != null) return null
        var file: File? = null
        try {
            val dir = File(context.cacheDir, "codex-camera")
            check(dir.isDirectory || dir.mkdirs()) { "无法建立拍照临时目录" }
            val temp = File.createTempFile("capture-", ".jpg", dir)
            file = temp
            val uri = FileProvider.getUriForFile(context, context.packageName + ".composerprovider", temp)
            if (!vm.beginImageSelection(thread)) {
                check(temp.delete()) { "无法清理拍照临时文件" }
                return null
            }
            cameraFile = temp
            cameraUri = uri
            return uri
        } catch (failure: Exception) {
            file?.delete()
            report(failure.message ?: "无法准备拍照")
            return null
        }
    }
    fun finishCamera(success: Boolean) {
        val file = cameraFile ?: return
        val uri = cameraUri
        cameraFile = null
        cameraUri = null
        vm.importPickedImages(if (success && uri != null) listOf(uri) else emptyList())
        vm.viewModelScope.launch {
            vm.attachmentImport.first { !it.inProgress }
            if (file.exists() && !file.delete()) report("无法清理拍照临时文件")
        }
    }
    fun pasteImage(thread: String?) {
        if (!vm.globalWorkSnapshot().canStartNewTask) return
        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
        val uris = if (clip != null && clip.description.hasMimeType("image/*"))
            (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        else emptyList()
        if (uris.isEmpty()) {
            report("剪贴板没有可读取的图片")
            return
        }
        if (vm.beginImageSelection(thread)) vm.importPickedImages(uris)
    }
    fun beginVoice(thread: String?, workspace: String): Intent? {
        if (!canRecognizeSpeech() || voiceOwner != null || !vm.globalWorkSnapshot().canStartNewTask) return null
        voiceOwner = Target(thread, workspace)
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "说出任务指令")
    }
    fun finishVoice(text: String?) {
        val target = voiceOwner ?: return
        voiceOwner = null
        val recognized = text?.trim()?.takeIf { it.isNotBlank() } ?: return
        vm.composerDrafts.update { drafts ->
            val old = drafts[ThreadComposerDrafts.key(target.thread)]?.text.orEmpty()
            ThreadComposerDrafts.text(drafts, target.thread, listOf(old, recognized).filter { it.isNotBlank() }.joinToString("\n"))
        }
    }
    fun pickerFailure(failure: Exception) = report(failure.message ?: "无法打开系统输入来源")
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
