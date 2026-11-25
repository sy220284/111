package io.github.sy220284.codexandroid

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Owns the complete read/write/readback transaction, across page changes. */
internal object AccountVoiceSettingsLease {
    const val key = "accountVoiceSettings"
    private val identityMutations = setOf("account/login/start", "account/logout", "accountSession/switch")

    fun blocks(method: String, loading: Set<String>): Boolean =
        key in loading && method in identityMutations

    fun acquire(loading: MutableStateFlow<Set<String>>): Boolean {
        val previous = loading.value
        if (previous.any { it == key || it == "account" || it == "accountSessionSwitch" }) return false
        return loading.compareAndSet(previous, previous + key)
    }

    fun release(loading: MutableStateFlow<Set<String>>) {
        loading.update { it - key }
    }
}
