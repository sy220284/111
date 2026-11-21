package io.github.sy220284.codexandroid

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * 所有全屏弹层截图共用这个验证入口。判据是 WindowManager 当前焦点窗口，
 * 能识别跨应用的系统 ANR 对话框；无障碍节点树只作为读不到焦点信息时的补充。
 * Android 35 某些屏宽下 getRootInActiveWindow() 会暂时返回 null，
 * 因此单独依赖该接口会错误拒绝正常的 Compose 弹层。
 */
internal fun captureVerifiedDisplayScreenshot(fileName: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val automation = instrumentation.uiAutomation
    val target = instrumentation.targetContext

    fun focusedWindow(): String {
        val descriptor = requireNotNull(automation.executeShellCommand("dumpsys window")) {
            "WindowManager 无法查询前台窗口：$fileName"
        }
        val dump = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader()
            .use { it.readText() }
        val current = dump.lineSequence()
            .map(String::trim)
            .lastOrNull { it.startsWith("mCurrentFocus=") }
            ?.substringAfter("mCurrentFocus=")?.trim().orEmpty()
        if (current.isNotBlank() && current != "null") return current
        // 某些模拟器在瞬时焦点交接期不会生成 mCurrentFocus；检查当前无障碍焦点。
        val accessible = automation.rootInActiveWindow?.packageName?.toString().orEmpty()
        check(accessible.isNotBlank()) {
            "系统未提供有效前台窗口信息：$fileName"
        }
        return accessible
    }

    fun checkOwner() {
        val focus = focusedWindow()
        check(focus.contains(target.packageName)) {
            "截图被其他程序或系统窗口遮挡：$fileName；当前前台=$focus"
        }
    }

    checkOwner()
    val image = requireNotNull(automation.takeScreenshot()) {
        "Android 全屏截图失败：$fileName"
    }
    checkOwner()
    val directory = requireNotNull(target.getExternalFilesDir(null)) {
        "设备未提供测试截图目录"
    }
    File(directory, fileName).outputStream().use { output ->
        check(image.compress(Bitmap.CompressFormat.PNG, 100, output)) {
            "无法写入 UI 截图：$fileName"
        }
    }
}
