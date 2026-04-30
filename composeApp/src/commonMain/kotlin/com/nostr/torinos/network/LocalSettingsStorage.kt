package com.nostr.torinos.network

expect object LocalSettingsStorage {
    suspend fun getString(key: String): String?
    suspend fun putString(key: String, value: String?)
}
