package io.github.sy220284.codexandroid

import org.json.JSONObject

/**
 * Validate a JSON-RPC response before it reaches a feature or UI reducer.
 * All Codex app-server request results consumed by this client use typed objects.
 * A missing/null/wrong-type result must not be interpreted as a successful {}.
 */
/** Only a validated server error is an explicit RPC rejection. */
internal class RpcServerRejection(
    val method: String,
    val code: Number,
    val serverMessage: String
) : IllegalStateException("$method：$serverMessage（错误代码：$code）")

internal object RpcResponseContract {
    fun requireResult(method: String, envelope: JSONObject): JSONObject {
        val hasResult = envelope.has("result")
        val hasError = envelope.has("error") && !envelope.isNull("error")
        check(hasResult != hasError) {
            "$method：内核响应必须包含有效结果或错误，且只能出现一种"
        }

        if (hasError) {
            val info = envelope.optJSONObject("error")
                ?: throw IllegalStateException("$method：内核返回的错误格式无效")
            val code = info.opt("code")
            val message = info.optString("message")
            check(code is Number && message.isNotBlank()) {
                "$method：内核错误缺少有效的代码或说明"
            }
            throw RpcServerRejection(method, code, message)
        }

        return envelope.optJSONObject("result")
            ?: throw IllegalStateException(
                "$method：内核返回的结果为空或类型错误，未执行成功状态更新"
            )
    }
}
