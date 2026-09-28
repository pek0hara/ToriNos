package com.nostr.torinos.emoji

import com.nostr.torinos.network.CustomEmoji
import com.nostr.torinos.network.LocalSettingsStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** 文字列のキーと値の保存先。テストでは差し替える。 */
internal interface EmojiKeyValueStore {
    suspend fun get(key: String): String?
    suspend fun put(key: String, value: String?)
}

internal object LocalSettingsKeyValueStore : EmojiKeyValueStore {
    override suspend fun get(key: String): String? = LocalSettingsStorage.getString(key)
    override suspend fun put(key: String, value: String?) = LocalSettingsStorage.putString(key, value)
}

/** アカウントごとの絵文字設定を JSON 1本で保存する（形式 v2）。 */
internal class EmojiPreferencesStorage(
    private val store: EmojiKeyValueStore = LocalSettingsKeyValueStore,
) {
    suspend fun load(pubkey: String): EmojiPreferences {
        store.get(key(pubkey))?.let { saved ->
            decode(saved)?.let { return it }
        }
        val migrated = migrateLegacy(pubkey) ?: return EmojiPreferences()
        save(pubkey, migrated)
        (LEGACY_KEYS.map { "${it}_$pubkey" } + LEGACY_KEYS).forEach { store.put(it, null) }
        return migrated
    }

    suspend fun save(pubkey: String, preferences: EmojiPreferences) {
        store.put(key(pubkey), json.encodeToString(StoredEmojiPreferences.serializer(), preferences.toStored()))
    }

    private fun decode(saved: String): EmojiPreferences? =
        runCatching { json.decodeFromString(StoredEmojiPreferences.serializer(), saved) }
            .getOrNull()
            ?.toPreferences()

    /**
     * 旧形式（キー5本）を読む。アカウント別のキーがなければ、アカウント導入前のグローバルキーを使う。
     * アドレスを持たない旧「手動登録」リストはお気に入りへ統合する（F1。同期ではすでにお気に入りとして送っている）。
     */
    private suspend fun migrateLegacy(pubkey: String): EmojiPreferences? {
        val scoped = LEGACY_KEYS.associateWith { store.get("${it}_$pubkey") }
        val values = if (scoped.values.any { it != null }) scoped else LEGACY_KEYS.associateWith { store.get(it) }
        if (values.values.all { it == null }) return null

        val flatEmojis = values[LEGACY_EMOJIS]?.decodeList(CustomEmoji.serializer()).orEmpty()
        val lists = values[LEGACY_LISTS]?.decodeList(LegacyEmojiList.serializer()).orEmpty()
        val favorites = values[LEGACY_FAVORITES]?.decodeList(CustomEmoji.serializer()).orEmpty()

        val sets = mutableListOf<RegisteredEmojiSet>()
        val manualEmojis = mutableListOf<CustomEmoji>()
        lists.forEach { list ->
            val address = list.address()
            if (address == null) {
                manualEmojis += list.emojis
            } else {
                sets += RegisteredEmojiSet(address, list.name, list.emojis)
            }
        }
        if (lists.isEmpty()) manualEmojis += flatEmojis

        val base = EmojiPreferences()
            .let { sets.fold(it) { acc, set -> acc.registerSet(set) } }
            .let { (favorites + manualEmojis).fold(it) { acc, emoji -> if (acc.isFavorite(emoji)) acc else acc.toggleFavorite(emoji) } }

        val legacyRecent = values[LEGACY_RECENT_REACTIONS]?.decodeList(StoredRecentReaction.serializer()).orEmpty()
            .mapNotNull { it.toRecent(base) }
            .ifEmpty {
                values[LEGACY_RECENT_SHORTCODES]?.decodeList(String.serializer()).orEmpty()
                    .mapNotNull { shortcode -> base.resolve(shortcode)?.let { RecentReaction.Custom(it) } }
            }
        val withRecent = legacyRecent.asReversed().fold(base) { acc, reaction -> acc.recordUse(reaction) }
        // 旧形式の内容はすでにリレーへ送信済み、または取り込み済みとみなす。
        return withRecent.copy(revision = 0, syncedRevision = 0)
    }

    private fun <T> String.decodeList(serializer: kotlinx.serialization.KSerializer<T>): List<T> =
        runCatching { json.decodeFromString(ListSerializer(serializer), this) }.getOrDefault(emptyList())

    private fun key(pubkey: String) = "emoji_preferences_v2_$pubkey"

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        const val LEGACY_EMOJIS = "custom_emojis"
        const val LEGACY_LISTS = "custom_emoji_lists"
        const val LEGACY_RECENT_SHORTCODES = "recent_custom_emojis"
        const val LEGACY_RECENT_REACTIONS = "recent_reactions"
        const val LEGACY_FAVORITES = "favorite_custom_emojis"
        val LEGACY_KEYS = listOf(
            LEGACY_EMOJIS,
            LEGACY_LISTS,
            LEGACY_FAVORITES,
            LEGACY_RECENT_SHORTCODES,
            LEGACY_RECENT_REACTIONS,
        )
    }
}

@Serializable
private data class StoredEmojiPreferences(
    val version: Int = 2,
    val sets: List<StoredEmojiSet> = emptyList(),
    val favorites: List<CustomEmoji> = emptyList(),
    val unresolvedSetAddresses: List<String> = emptyList(),
    val recent: List<StoredRecentReaction> = emptyList(),
    val revision: Long = 0,
    val syncedRevision: Long = 0,
)

@Serializable
private data class StoredEmojiSet(
    val address: String,
    val title: String,
    val emojis: List<CustomEmoji>,
)

/** 旧形式と v2 で共通の最近使ったリアクション。 */
@Serializable
private data class StoredRecentReaction(
    val kind: String,
    val value: String,
    val imageUrl: String = "",
) {
    fun toRecent(preferences: EmojiPreferences? = null): RecentReaction? = when (kind) {
        UNICODE -> value.takeIf { it.isNotBlank() }?.let { RecentReaction.Unicode(it) }
        CUSTOM -> imageUrl.takeIf { it.isNotBlank() }?.let { RecentReaction.Custom(CustomEmoji(value, it)) }
            ?: preferences?.resolve(value)?.let { RecentReaction.Custom(it) }
        else -> null
    }

    companion object {
        const val UNICODE = "unicode"
        const val CUSTOM = "custom"

        fun from(reaction: RecentReaction) = when (reaction) {
            is RecentReaction.Unicode -> StoredRecentReaction(UNICODE, reaction.value)
            is RecentReaction.Custom -> StoredRecentReaction(CUSTOM, reaction.emoji.shortcode, reaction.emoji.imageUrl)
        }
    }
}

/** 旧形式の登録済みリスト。id は `30030:pk:d`、`pk:d`、d（authorPubkey 併用）、`manual` のいずれか。 */
@Serializable
private data class LegacyEmojiList(
    val id: String = "",
    val name: String = "",
    val emojis: List<CustomEmoji> = emptyList(),
    val authorPubkey: String = "",
) {
    fun address(): EmojiSetAddress? {
        EmojiSetAddress.parse(id)?.let { return it }
        val parts = id.split(':', limit = 2)
        if (parts.size == 2) return EmojiSetAddress.of(parts[0], parts[1])
        return EmojiSetAddress.of(authorPubkey, id)
    }
}

private fun EmojiPreferences.toStored() = StoredEmojiPreferences(
    sets = sets.map { StoredEmojiSet(it.address.value, it.title, it.emojis) },
    favorites = favorites,
    unresolvedSetAddresses = unresolvedSetAddresses.map { it.value },
    recent = recent.map(StoredRecentReaction::from),
    revision = revision,
    syncedRevision = syncedRevision,
)

private fun StoredEmojiPreferences.toPreferences(): EmojiPreferences {
    val registered = sets.mapNotNull { stored ->
        EmojiSetAddress.parse(stored.address)?.let { RegisteredEmojiSet(it, stored.title, stored.emojis) }
    }
    return EmojiPreferences(
        sets = registered,
        favorites = favorites,
        unresolvedSetAddresses = unresolvedSetAddresses.mapNotNull(EmojiSetAddress::parse).toSet(),
        recent = recent.mapNotNull { it.toRecent() },
        revision = revision,
        syncedRevision = syncedRevision,
    )
}
