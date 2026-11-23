package io.github.sy220284.codexandroid

import org.junit.Assert.assertEquals
import org.junit.Test

class UiCopyPolicyTest {
    @Test fun pluginInstallLabelsReflectRealInstallationPolicy() {
        assertEquals("已安装", PresentationPolicy.pluginInstallStatus(true, "NOT_AVAILABLE"))
        assertEquals("暂不可安装", PresentationPolicy.pluginInstallStatus(false, "NOT_AVAILABLE"))
        assertEquals("可安装", PresentationPolicy.pluginInstallStatus(false, ""))
    }
}
