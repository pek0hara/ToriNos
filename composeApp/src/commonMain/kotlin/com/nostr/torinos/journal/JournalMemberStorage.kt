package com.nostr.torinos.journal

import com.nostr.torinos.network.LocalSettingsStorage
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** 自分のジャーナルに追加したメンバー（自分を除く）を、アカウントごとに端末へ保存する。 */
internal interface JournalMemberStorage {
    suspend fun load(ownPubkey: String): List<String>
    suspend fun save(ownPubkey: String, members: List<String>)

    object Local : JournalMemberStorage {
        override suspend fun load(ownPubkey: String): List<String> =
            LocalSettingsStorage.getString(key(ownPubkey))
                ?.let { saved -> runCatching { json.decodeFromString(serializer, saved) }.getOrNull() }
                .orEmpty()

        override suspend fun save(ownPubkey: String, members: List<String>) =
            LocalSettingsStorage.putString(key(ownPubkey), json.encodeToString(serializer, members))

        private fun key(ownPubkey: String) = "journal_members_$ownPubkey"
        private val serializer = ListSerializer(String.serializer())
        private val json = Json { ignoreUnknownKeys = true }
    }
}
