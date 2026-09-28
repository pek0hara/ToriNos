package com.nostr.torinos.network

import kotlinx.browser.localStorage

actual object LocalSettingsStorage {
    actual suspend fun getString(key: String): String? =
        localStorage.getItem(key)

    actual suspend fun putString(key: String, value: String?) {
        if (value == null) {
            localStorage.removeItem(key)
        } else {
            localStorage.setItem(key, value)
        }
    }
}
