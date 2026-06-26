package com.nostr.torinos.network

import com.nostr.torinos.model.GroupRef
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
private data class StoredGroup(
    val relayUrl: String,
    val groupId: String,
    val name: String? = null,
    val lastReadAt: Long = 0,
    val createEventId: String? = null,
    val createdAt: Long? = null,
    val metadataVerified: Boolean = false,
    val creatorPubkey: String? = null,
    val selfAdminGranted: Boolean = false,
)

data class SavedNip29Group(
    val ref: GroupRef,
    val name: String? = null,
    val lastReadAt: Long = 0,
    val createEventId: String? = null,
    val createdAt: Long? = null,
    val metadataVerified: Boolean = false,
    val creatorPubkey: String? = null,
    val selfAdminGranted: Boolean = false,
)

object Nip29GroupStore {
    private const val KEY_PREFIX = "nip29_groups_"
    private val json = Json { ignoreUnknownKeys = true }
    private val _removedGroups = MutableSharedFlow<GroupRef>(extraBufferCapacity = 16)
    val removedGroups: SharedFlow<GroupRef> = _removedGroups.asSharedFlow()

    suspend fun load(accountPubkey: String?): List<SavedNip29Group> {
        val raw = LocalSettingsStorage.getString(key(accountPubkey)) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(StoredGroup.serializer()), raw)
                .mapNotNull { stored ->
                    runCatching {
                        SavedNip29Group(
                            ref = GroupRef.create(stored.relayUrl, stored.groupId),
                            name = stored.name,
                            lastReadAt = stored.lastReadAt,
                            createEventId = stored.createEventId,
                            createdAt = stored.createdAt,
                            metadataVerified = stored.metadataVerified,
                            creatorPubkey = stored.creatorPubkey,
                            selfAdminGranted = stored.selfAdminGranted,
                        )
                    }.getOrNull()
                }
        }.getOrDefault(emptyList())
    }

    suspend fun save(accountPubkey: String?, groups: List<SavedNip29Group>) {
        val stored = groups.distinctBy { it.ref.key }.map {
            StoredGroup(
                relayUrl = it.ref.relayUrl,
                groupId = it.ref.groupId,
                name = it.name,
                lastReadAt = it.lastReadAt,
                createEventId = it.createEventId,
                createdAt = it.createdAt,
                metadataVerified = it.metadataVerified,
                creatorPubkey = it.creatorPubkey,
                selfAdminGranted = it.selfAdminGranted,
            )
        }
        LocalSettingsStorage.putString(
            key(accountPubkey),
            json.encodeToString(ListSerializer(StoredGroup.serializer()), stored),
        )
    }

    suspend fun markRead(accountPubkey: String?, ref: GroupRef, createdAt: Long) {
        val groups = load(accountPubkey)
        save(
            accountPubkey,
            groups.map {
                if (it.ref == ref) it.copy(lastReadAt = maxOf(it.lastReadAt, createdAt)) else it
            },
        )
    }

    suspend fun remove(accountPubkey: String?, ref: GroupRef): List<SavedNip29Group> {
        val current = load(accountPubkey)
        val remaining = current.filterNot { it.ref == ref }
        save(accountPubkey, remaining)
        if (remaining.size != current.size) {
            _removedGroups.emit(ref)
        }
        return remaining
    }

    private fun key(accountPubkey: String?): String =
        KEY_PREFIX + (accountPubkey ?: "guest")
}
