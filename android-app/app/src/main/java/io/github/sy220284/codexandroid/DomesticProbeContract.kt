package io.github.sy220284.codexandroid

import org.json.JSONObject

internal object DomesticProbeContract {
    fun params(provider: String, model: String, confirmed: Boolean): JSONObject {
        require(provider.isNotBlank() && model.isNotBlank()) { "必须选择服务商及模型" }
        require(confirmed) { "必须确认真实验证可能计费" }
        return JSONObject().put("modelProvider", provider).put("model", model)
            .put("confirmPaidRequest", confirmed)
    }
}
