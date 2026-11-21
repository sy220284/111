package io.github.sy220284.codexandroid

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * 所有“全屏/弹层”真实截图的单一采集入口。
 *
 * Compose 语义测试可以正确通过，即使无关系统进程的 ANR 警告浮在截图上；
 * 所以截图证据必须在采集前后证明当前焦点窗口属于被测 APP。
 * 避免系统错误提示被错误保存在合格 UI 证据中。
 */
internal fun captureVerifiedDisplayScreenshot(fileName: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val automation = instrumentation.uiAutomation
    val target = instrumentation.targetContext
    fun checkOwner() {
        val foreground = requireNotNull(automation.rootInActiveWindow) {
            "无法识别前台窗口，截图未经过可信验证：$fileName"
        }.packageName?.toString()
        check(foreground == target.packageName) {
            "截图被其他程序或系统窗口遮挡：$fileName；前台=$foreground"
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
