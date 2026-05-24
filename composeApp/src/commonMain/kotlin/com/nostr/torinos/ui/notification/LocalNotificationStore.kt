package com.nostr.torinos.ui.notification

import com.nostr.torinos.network.LocalSettingsStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

@Serializable
data class StoredNotifications(
    val items: List<NotificationItem> = emptyList(),
    val readItemIds: Set<String> = emptySet(),
)

object LocalNotificationStore {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(ownPubkey: String): StoredNotifications {
        val saved = LocalSettingsStorage.getString(notificationsKey(ownPubkey)) ?: return StoredNotifications()
        return runCatching {
            json.decodeFromString(StoredNotifications.serializer(), saved)
        }.getOrNull() ?: StoredNotifications()
    }

    suspend fun save(ownPubkey: String, notifications: StoredNotifications) {
        LocalSettingsStorage.putString(
            notificationsKey(ownPubkey),
            json.encodeToString(StoredNotifications.serializer(), notifications),
        )
    }

    suspend fun loadKnownFollowers(ownPubkey: String): List<String>? {
        val saved = LocalSettingsStorage.getString(knownFollowersKey(ownPubkey)) ?: return null
        return runCatching {
            json.decodeFromString(ListSerializer(String.serializer()), saved)
        }.getOrNull()
    }

    suspend fun saveKnownFollowers(ownPubkey: String, pubkeys: List<String>) {
        LocalSettingsStorage.putString(
            knownFollowersKey(ownPubkey),
            json.encodeToString(ListSerializer(String.serializer()), pubkeys),
        )
    }

    private fun notificationsKey(ownPubkey: String): String = "notifications_$ownPubkey"

    private fun knownFollowersKey(ownPubkey: String): String = "notification_known_followers_$ownPubkey"
}
