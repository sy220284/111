package io.github.sy220284.codexandroid

import org.json.JSONObject

internal enum class AccountSetupState { Unknown, Required, Optional, Authorized }

internal enum class ChatSetupHint(val route: String) {
    Authorize("account"), Models("providers")
}

/** Only actual app-server account/model replies can expose an unmet prerequisite. */
internal object ChatSetupPolicy {
    fun accountState(payload: JSONObject?): AccountSetupState {
        if (payload == null || !payload.has("account")) return AccountSetupState.Unknown
        if (payload.optJSONObject("account") != null) return AccountSetupState.Authorized
        if (!payload.isNull("account")) return AccountSetupState.Unknown
        return when (payload.opt("requiresOpenaiAuth")) {
            true -> AccountSetupState.Required
            false -> AccountSetupState.Optional
            else -> AccountSetupState.Unknown
        }
    }

    fun hint(connected: Boolean, account: JSONObject?, models: JSONObject?): ChatSetupHint? {
        if (!connected) return null
        if (accountState(account) == AccountSetupState.Required) return ChatSetupHint.Authorize
        // Only a complete, successful empty page is meaningful; absence/error is unknown.
        if (models != null && models.optJSONArray("data")?.length() == 0 &&
            models.has("nextCursor") && models.isNull("nextCursor")) return ChatSetupHint.Models
        return null
    }
}
