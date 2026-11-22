package io.github.sy220284.codexandroid

import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/** Host capability failures are propagated as user-visible protocol receipts. */
internal object HostRequestFeedback {
    fun report(params: JSONObject, errors: MutableStateFlow<Map<String, String>>) {
        val reason = params.optString("reason").ifBlank {
            "内核请求了当前客户端未启用的宿主能力，可在诊断中查看详细原因"
        }
        errors.value = errors.value + ("protocol" to reason)
    }
}
