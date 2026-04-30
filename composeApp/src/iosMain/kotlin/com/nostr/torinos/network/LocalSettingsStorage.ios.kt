package com.nostr.torinos.network

import platform.Foundation.NSUserDefaults

actual object LocalSettingsStorage {
    private val defaults get() = NSUserDefaults.standardUserDefaults

    actual suspend fun getString(key: String): String? =
        defaults.stringForKey(key)

    actual suspend fun putString(key: String, value: String?) {
        if (value == null) {
            defaults.removeObjectForKey(key)
        } else {
            defaults.setObject(value, forKey = key)
        }
        defaults.synchronize()
    }
}
