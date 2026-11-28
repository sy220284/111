package io.github.sy220284.codexandroid

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Test-only theme selection is explicit for every device configuration. */
internal fun applyAuditTheme() {
    val theme = InstrumentationRegistry.getArguments().getString("auditTheme") ?: return
    require(theme == "light" || theme == "dark") { "未知验收主题：$theme" }
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        UiPreferences.setDarkMode(theme == "dark")
    }
}

/** AOSP ClipboardOverlayWindow 的标题固定为 ClipboardOverlay，且窗口不获取焦点。 */
internal fun hasClipboardOverlayWindow(dump: String): Boolean =
    Regex("""(?m)^\s*Window #\d+ Window\{[^\n]*\bClipboardOverlay\b""").containsMatchIn(dump)

/**
 * 所有全屏弹层截图共用这个验证入口。判据是 WindowManager 当前焦点窗口，
 * 能识别跨应用的系统 ANR 对话框；无障碍节点树只作为读不到焦点信息时的补充。
 * Android 某些系统版本及屏宽下 getRootInActiveWindow() 会暂时返回 null，
 * 因此单独依赖该接口会错误拒绝正常的 Compose 弹层。
 */
internal fun captureVerifiedDisplayScreenshot(fileName: String, assertSubject: (() -> Unit)? = null) {
    // Keep rejected evidence out of the gallery without altering functional tests.
    try {
        captureOwnedDisplayScreenshot(fileName, assertSubject)
    } catch (failure: Exception) {
        InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
            ?.let { File(it, fileName).delete() }
        android.util.Log.w("UiScreenshotObservation", "SCREENSHOT_FAILED $fileName", failure)
        println("SCREENSHOT_FAILED $fileName: " + failure.message)

    }
}

private fun captureOwnedDisplayScreenshot(fileName: String, assertSubject: (() -> Unit)?) {
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

    fun checkSubject() {
        val expectedTheme = if (fileName == "audit-dark-200font-appearance-android.png") "dark"
            else InstrumentationRegistry.getArguments().getString("auditTheme")
        if (expectedTheme != null) {
            check(UiPreferences.darkMode.value == (expectedTheme == "dark")) {
                "截图主题与验收配置不一致：$fileName / $expectedTheme"
            }
        }
        try {
            assertSubject?.invoke()
        } catch (failure: AssertionError) {
            throw IllegalStateException("截图实际页面不符合预期：$fileName", failure)
        }
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
    checkSubject()
    val image = requireNotNull(automation.takeScreenshot()) {
        "Android 全屏截图失败：$fileName"
    }
    try {
        check(!hasClipboardOverlayWindow(checkOwner())) { "截图期间出现系统剪贴板浮层：$fileName" }
        checkSubject()
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

/** Raw diagnostic only: retain overlays causing failure; never accepted as successful UI evidence. */
internal fun captureFailureDisplayScreenshot(fileName: String) {
    require(fileName.endsWith("-failure.png")) { "失败诊断须独立命名" }
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "无法保存失败画面" }
    try {
        val directory = requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        File(directory, fileName).outputStream().use {
            check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) { "无法写入失败画面" }
        }
    } finally { image.recycle() }
}
