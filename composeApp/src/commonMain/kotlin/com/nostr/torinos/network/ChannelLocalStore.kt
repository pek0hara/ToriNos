package com.nostr.torinos.network

import com.nostr.torinos.model.ChannelMeta
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.util.cacheTraceLog
import com.nostr.torinos.util.logException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

data class ChannelReadingPosition(
    val messageId: String,
    val createdAt: Long?,
    val scrollOffset: Int = 0,
)

@Serializable
data class ChannelLatestMessagePreview(
    val eventId: String,
    val createdAt: Long,
    val pubkey: String,
    val contentPreview: String,
)

/**
 * チャンネル1件分の端末ローカル状態(第16.12節)。メッセージ本体の履歴は持たない。
 *
 * [observedRelays]はkind 40を観測したリレー(一覧のリレー別表示用)であり、
 * [ChannelMeta.relays]の推奨リレーとは別概念として保持する(第16.1節の原則4)。
 * [ownerPubkey]が空の状態は、kind 40未取得のまま既読位置だけ保存された暫定行を表す。
 */
@Serializable
data class ChannelLocalState(
    val channelId: String,
    val ownerPubkey: String = "",
    val channelCreatedAt: Long = 0,
    val metadataEventId: String = "",
    val metadataKind: Int = 40,
    val metadataCreatedAt: Long = 0,
    val meta: ChannelMeta = ChannelMeta(),
    val observedRelays: List<String> = emptyList(),
    val isFavorite: Boolean = false,
    /** null は一度も開いていないことを表す(第16.12.4節)。 */
    val lastReadAt: Long? = null,
    val lastScrolledMessageId: String? = null,
    val lastScrolledCreatedAt: Long? = null,
    val lastScrolledOffset: Int = 0,
    val latestMessage: ChannelLatestMessagePreview? = null,
) {
    val hasMetadata: Boolean get() = ownerPubkey.isNotEmpty() && metadataEventId.isNotEmpty()

    val readingPosition: ChannelReadingPosition?
        get() = lastScrolledMessageId?.let {
            ChannelReadingPosition(it, lastScrolledCreatedAt, lastScrolledOffset)
        }
}

/** `ChannelLocalStateStore`の永続化先。単一キーへJSON文字列をまるごと保存する。 */
internal interface ChannelLocalStorage {
    suspend fun read(): String?
    suspend fun write(value: String)
}

private object LocalSettingsChannelStorage : ChannelLocalStorage {
    private const val KEY = "channel_local_state_v1"

    override suspend fun read(): String? = LocalSettingsStorage.getString(KEY)

    override suspend fun write(value: String) {
        LocalSettingsStorage.putString(KEY, value)
    }
}

/**
 * `Map<channelId, ChannelLocalState>`をメモリに保持し、[ChannelLocalStorage]へJSONで永続化する(第16.12節)。
 * 書込失敗はネットワーク表示を止めないよう、ログに残して握りつぶす(第16.20節)。
 */
internal open class ChannelLocalStateStore(
    private val storage: ChannelLocalStorage,
    private val scope: CoroutineScope,
    private val persistDebounceMs: Long = PERSIST_DEBOUNCE_MS,
    private val maxStates: Int = MAX_STATES,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }
    private val serializer = MapSerializer(String.serializer(), ChannelLocalState.serializer())
    private val stateMutex = Mutex()
    private val writeMutex = Mutex()
    private val states = MutableStateFlow<Map<String, ChannelLocalState>?>(null)
    private var debouncedPersistJob: Job? = null

    fun observe(relayUrl: String): Flow<List<ChannelLocalState>> = flow {
        ensureLoaded()
        val normalized = normalizeRelayUrl(relayUrl) ?: relayUrl
        emitAll(
            states.filterNotNull()
                .map { all -> all.values.filter { it.hasMetadata && normalized in it.observedRelays } }
                .distinctUntilChanged(),
        )
    }

    suspend fun get(channelId: String): ChannelLocalState? = loaded()[channelId]

    /** kind 40 を観測した。既に kind 41 由来の実効メタデータを保持していれば上書きしない。 */
    suspend fun recordChannelCreate(event: NostrEvent, meta: ChannelMeta, observedRelayUrl: String?) = update { all ->
        val current = all[event.id]
        val keepsMetadata = current != null && current.hasMetadata && current.metadataEventId != event.id
        val base = current ?: ChannelLocalState(channelId = event.id)
        val next = base.copy(
            ownerPubkey = event.pubkey,
            channelCreatedAt = event.createdAt,
            metadataEventId = if (keepsMetadata) base.metadataEventId else event.id,
            metadataKind = if (keepsMetadata) base.metadataKind else event.kind,
            metadataCreatedAt = if (keepsMetadata) base.metadataCreatedAt else event.createdAt,
            meta = if (keepsMetadata) base.meta else meta,
            observedRelays = base.observedRelays.withRelay(observedRelayUrl),
        )
        all + (event.id to next)
    }

    /**
     * resolver が決めた実効メタデータを保存する。kind 41 の event ID を channel ID として保存しないよう、
     * [channelCreateEvent]と[effectiveEvent]を分けて受け取る。保存済みの方が新しい場合は維持する。
     */
    suspend fun upsertChannelMetadata(
        channelCreateEvent: NostrEvent,
        effectiveEvent: NostrEvent,
        metadata: ChannelMeta,
        observedRelayUrl: String? = null,
    ) = update { all ->
        val channelId = channelCreateEvent.id
        val base = all[channelId] ?: ChannelLocalState(channelId = channelId)
        val storedIsNewer = base.hasMetadata && base.ownerPubkey == channelCreateEvent.pubkey &&
            isNewer(base.metadataCreatedAt, base.metadataEventId, effectiveEvent.createdAt, effectiveEvent.id)
        val next = base.copy(
            ownerPubkey = channelCreateEvent.pubkey,
            channelCreatedAt = channelCreateEvent.createdAt,
            metadataEventId = if (storedIsNewer) base.metadataEventId else effectiveEvent.id,
            metadataKind = if (storedIsNewer) base.metadataKind else effectiveEvent.kind,
            metadataCreatedAt = if (storedIsNewer) base.metadataCreatedAt else effectiveEvent.createdAt,
            meta = if (storedIsNewer) base.meta else metadata.copy(relays = normalizeRelayUrls(metadata.relays)),
            observedRelays = base.observedRelays.withRelay(observedRelayUrl),
        )
        all + (channelId to next)
    }

    /** 一覧プレビュー用の直近1件だけを保持する。高頻度に呼ばれるため永続化は debounce する。 */
    suspend fun recordLatestMessage(channelId: String, event: NostrEvent) = update(debounced = true) { all ->
        val current = all[channelId]?.takeIf { it.hasMetadata } ?: return@update all
        val latest = current.latestMessage
        if (latest != null && !isNewer(event.createdAt, event.id, latest.createdAt, latest.eventId)) {
            return@update all
        }
        val preview = ChannelLatestMessagePreview(
            eventId = event.id,
            createdAt = event.createdAt,
            pubkey = event.pubkey,
            contentPreview = event.content.take(PREVIEW_MAX_CHARS),
        )
        all + (channelId to current.copy(latestMessage = preview))
    }

    /** 削除要求を送ったメッセージがプレビューに残らないようにする。次の活動取得で埋め直される。 */
    suspend fun clearLatestMessage(channelId: String, eventId: String) = update { all ->
        val current = all[channelId]?.takeIf { it.latestMessage?.eventId == eventId } ?: return@update all
        all + (channelId to current.copy(latestMessage = null))
    }

    suspend fun markRead(channelId: String, readAt: Long) = update { all ->
        val current = all[channelId] ?: ChannelLocalState(channelId = channelId)
        val lastReadAt = current.lastReadAt
        if (lastReadAt != null && lastReadAt >= readAt) return@update all
        all + (channelId to current.copy(lastReadAt = readAt))
    }

    suspend fun saveReadingPosition(channelId: String, position: ChannelReadingPosition) = update { all ->
        val current = all[channelId] ?: ChannelLocalState(channelId = channelId)
        all + (
            channelId to current.copy(
                lastScrolledMessageId = position.messageId,
                lastScrolledCreatedAt = position.createdAt,
                lastScrolledOffset = position.scrollOffset,
            )
            )
    }

    suspend fun setFavorite(channelId: String, isFavorite: Boolean) = update { all ->
        val current = all[channelId] ?: return@update all
        all + (channelId to current.copy(isFavorite = isFavorite))
    }

    suspend fun deleteChannel(channelId: String) = update { all -> all - channelId }

    /** 指定リレーで観測した非お気に入りチャンネルを一覧から外す。他リレーでも観測済みなら既読状態だけ消す。 */
    suspend fun deleteNonFavorites(relayUrl: String) = update { all ->
        val normalized = normalizeRelayUrl(relayUrl) ?: relayUrl
        buildMap {
            all.forEach { (channelId, state) ->
                if (state.isFavorite || normalized !in state.observedRelays) {
                    put(channelId, state)
                    return@forEach
                }
                val remaining = state.observedRelays - normalized
                if (remaining.isNotEmpty()) {
                    put(
                        channelId,
                        state.copy(
                            observedRelays = remaining,
                            lastReadAt = null,
                            lastScrolledMessageId = null,
                            lastScrolledCreatedAt = null,
                            lastScrolledOffset = 0,
                        ),
                    )
                }
            }
        }
    }

    /** debounce 中の書込を即座に完了させる。 */
    suspend fun flush() {
        debouncedPersistJob?.cancel()
        persist()
    }

    private suspend fun update(
        debounced: Boolean = false,
        transform: (Map<String, ChannelLocalState>) -> Map<String, ChannelLocalState>,
    ) {
        val changed = stateMutex.withLock {
            val before = loadLocked()
            val after = transform(before).evictOverflow()
            if (after == before) return@withLock false
            states.value = after
            true
        }
        if (!changed) return
        if (debounced) {
            schedulePersist()
        } else {
            debouncedPersistJob?.cancel()
            persist()
        }
    }

    private fun schedulePersist() {
        if (debouncedPersistJob?.isActive == true) return
        debouncedPersistJob = scope.launch {
            delay(persistDebounceMs)
            persist()
        }
    }

    private suspend fun persist() {
        writeMutex.withLock {
            // 書込直前の最新スナップショットを使うため、直列化さえすれば古い値で上書きしない。
            val snapshot = states.value ?: return
            try {
                storage.write(json.encodeToString(serializer, snapshot))
                cacheTraceLog { "[ChannelLocalStore] persisted channels=${snapshot.size}" }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logException("ChannelLocalStore", error, "Could not persist channel local state")
            }
        }
    }

    private suspend fun ensureLoaded() {
        if (states.value == null) stateMutex.withLock { loadLocked() }
    }

    private suspend fun loaded(): Map<String, ChannelLocalState> =
        states.value ?: stateMutex.withLock { loadLocked() }

    private suspend fun loadLocked(): Map<String, ChannelLocalState> {
        states.value?.let { return it }
        val decoded = try {
            storage.read()?.let { raw -> json.decodeFromString(serializer, raw) }.orEmpty()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logException("ChannelLocalStore", error, "Could not load channel local state")
            emptyMap()
        }
        states.value = decoded
        return decoded
    }

    /** 上限超過時は、非お気に入り・未開封・活動の古いものから落とす。 */
    private fun Map<String, ChannelLocalState>.evictOverflow(): Map<String, ChannelLocalState> {
        if (size <= maxStates) return this
        val evictable = values
            .filterNot { it.isFavorite }
            .sortedWith(
                compareBy<ChannelLocalState> { it.lastReadAt != null }
                    .thenBy { it.latestMessage?.createdAt ?: it.channelCreatedAt },
            )
            .take(size - maxStates)
            .map { it.channelId }
            .toSet()
        return filterKeys { it !in evictable }
    }

    private fun List<String>.withRelay(relayUrl: String?): List<String> {
        val normalized = relayUrl?.let(::normalizeRelayUrl) ?: return this
        if (normalized in this) return this
        return (this + normalized).takeLast(MAX_OBSERVED_RELAYS)
    }

    companion object {
        const val PERSIST_DEBOUNCE_MS = 400L
        const val MAX_STATES = 1_000
        const val MAX_OBSERVED_RELAYS = 20
        const val PREVIEW_MAX_CHARS = 200

        /**
         * [aCreatedAt]/[aId] が厳密に新しいか。`ChannelMetadataResolver` と同じく、created_at が同じなら
         * event ID が小さい方を新しいとみなす(NIP-01 の replaceable event の慣習)。
         */
        fun isNewer(aCreatedAt: Long, aId: String, bCreatedAt: Long, bId: String): Boolean =
            aCreatedAt > bCreatedAt || (aCreatedAt == bCreatedAt && aId < bId)
    }
}

internal object ChannelLocalStore : ChannelLocalStateStore(
    storage = LocalSettingsChannelStorage,
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
)

/** Room/SQLite 時代(DB v1〜v7)のキャッシュファイルを削除する(第22章)。存在しなければ何もしない。 */
internal expect suspend fun deleteLegacyChannelCacheDatabase()
