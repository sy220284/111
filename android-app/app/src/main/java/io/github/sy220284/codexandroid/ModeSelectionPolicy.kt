package io.github.sy220284.codexandroid

/** 所有协作模式入口共享同一可用性判断，避免页面与弹层冲突。 */
internal object ModeSelectionPolicy {
    fun maySelect(mode: String, planSupported: Boolean, running: Boolean, refreshing: Boolean): Boolean =
        !running && !refreshing && (mode == "default" || (mode == "plan" && planSupported))
}
