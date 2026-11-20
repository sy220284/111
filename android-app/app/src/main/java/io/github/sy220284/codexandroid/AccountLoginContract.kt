package io.github.sy220284.codexandroid

import org.json.JSONObject

/** Typed reader for the official account/login/start discriminated response. */
object AccountLoginContract {
    data class Browser(val id: String, val url: String)
    data class Device(val id: String, val url: String, val code: String)
    data class Completion(val success: Boolean, val error: String)

    fun browser(result: JSONObject): Browser {
        require(result.optString("type") == "chatgpt") {
            "运行核心没有返回 ChatGPT 浏览器登录结果"
        }
        val id = result.optString("loginId")
        val url = result.optString("authUrl")
        require(id.isNotBlank() && url.startsWith("https://")) {
            "浏览器授权地址或登录标识缺失"
        }
        return Browser(id, url)
    }

    fun device(result: JSONObject): Device {
        require(result.optString("type") == "chatgptDeviceCode") {
            "运行核心没有返回 ChatGPT 设备验证码"
        }
        val id = result.optString("loginId")
        val url = result.optString("verificationUrl")
        val code = result.optString("userCode")
        require(id.isNotBlank() && url.startsWith("https://") && code.isNotBlank()) {
            "设备验证码登录地址、标识或代码缺失"
        }
        return Device(id, url, code)
    }

    /** Ignore late notifications from an earlier login when a newer attempt is pending. */
    fun completion(params: JSONObject, activeId: String): Completion? {
        val eventId = params.optString("loginId")
        if (activeId.isNotBlank() && eventId.isNotBlank() && activeId != eventId) return null
        return Completion(params.optBoolean("success", false), params.optString("error"))
    }
}
