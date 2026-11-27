package io.github.sy220284.codexandroid

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** AOSP ClipboardOverlayWindow 的标题固定为 ClipboardOverlay，且窗口不获取焦点。 */
internal fun hasClipboardOverlayWindow(dump: String): Boolean =
    Regex("""(?m)^\s*Window #\d+ Window\{[^\n]*\bClipboardOverlay\b""").containsMatchIn(dump)

/**
 * 所有全屏弹层截图共用这个验证入口。判据是 WindowManager 当前焦点窗口，
 * 能识别跨应用的系统 ANR 对话框；无障碍节点树只作为读不到焦点信息时的补充。
 * Android 35 某些屏宽下 getRootInActiveWindow() 会暂时返回 null，
 * 因此单独依赖该接口会错误拒绝正常的 Compose 弹层。
 */
internal fun captureVerifiedDisplayScreenshot(fileName: String) {
    // Evidence collection is observational. Keep invalid images out of the
    // gallery and report the failure without changing functional assertions.
    try {
        captureOwnedDisplayScreenshot(fileName)
    } catch (failure: Exception) {
        android.util.Log.w("UiScreenshotObservation", "SCREENSHOT_FAILED $fileName", failure)
        println("SCREENSHOT_FAILED $fileName: " + failure.message)
    }
}

private fun captureOwnedDisplayScreenshot(fileName: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val automation = instrumentation.uiAutomation
    val target = instrumentation.targetContext

    fun focusedWindow(): Pair<String, String> {
        val descriptor = requireNotNull(automation.executeShellCommand("dumpsys window")) {
            "WindowManager 无法查询前台窗口：$fileName"
        }
        val dump = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader()
            .use { it.readText() }
        val current = dump.lineSequence()
            .map(String::trim)
            .lastOrNull { it.startsWith("mCurrentFocus=") }
            ?.substringAfter("mCurrentFocus=")?.trim().orEmpty()
        if (current.isNotBlank() && current != "null") return current to dump
        // 某些模拟器在瞬时焦点交接期不会生成 mCurrentFocus；检查当前无障碍焦点。
        val accessible = automation.rootInActiveWindow?.packageName?.toString().orEmpty()
        check(accessible.isNotBlank()) {
            "系统未提供有效前台窗口信息：$fileName"
        }
        return accessible to dump
    }

    fun checkOwner(): String {
        val (focus, dump) = focusedWindow()
        // A popup title is not an ownership proof. Resolve its exact Window
        // token in dumpsys and verify the owning app UID and package.
        val token = Regex("""Window\{([^\s}]+)""").find(focus)?.groupValues?.get(1)
        val ownerBlock = token?.let { id ->
            val headers = Regex("""(?m)^\s*Window #\d+ Window\{([^\s}]+)[^\n]*""")
                .findAll(dump).toList()
            val index = headers.indexOfFirst { it.groupValues[1] == id }
            if (index < 0) null else dump.substring(headers[index].range.first,
                headers.getOrNull(index + 1)?.range?.first ?: dump.length)
        }
        val ownedPopup = focus.contains("Pop-Up Window") && ownerBlock != null &&
            Regex("""\bmOwnerUid=${target.applicationInfo.uid}\b""").containsMatchIn(ownerBlock) &&
            Regex("""\bpackage=${Regex.escape(target.packageName)}(?:\s|$)""").containsMatchIn(ownerBlock)
        check(focus.contains(target.packageName) || ownedPopup) {
            "截图被其他程序或系统窗口遮挡：$fileName；当前前台=$focus"
        }
        return dump
    }

    // 系统复制预览不夺取焦点，必须等其真实退场；不裁图、不遮盖、不发送返回键。
    val deadline = SystemClock.elapsedRealtime() + 10_000L
    var dump = checkOwner()
    while (hasClipboardOverlayWindow(dump) && SystemClock.elapsedRealtime() < deadline) {
        SystemClock.sleep(100L)
        dump = checkOwner()
    }
    check(!hasClipboardOverlayWindow(dump)) { "系统剪贴板浮层仍遮挡截图：$fileName" }
    val image = requireNotNull(automation.takeScreenshot()) {
        "Android 全屏截图失败：$fileName"
    }
    try {
        check(!hasClipboardOverlayWindow(checkOwner())) { "截图期间出现系统剪贴板浮层：$fileName" }
        val directory = requireNotNull(target.getExternalFilesDir(null)) {
            "设备未提供测试截图目录"
        }
        val pending = File(directory, "$fileName.part")
        try {
            pending.outputStream().use { output ->
                check(image.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "无法写入 UI 截图：$fileName"
                }
            }
            check(pending.renameTo(File(directory, fileName))) { "无法提交 UI 截图：$fileName" }
        } finally { pending.delete() }
    } finally { image.recycle() }
}
