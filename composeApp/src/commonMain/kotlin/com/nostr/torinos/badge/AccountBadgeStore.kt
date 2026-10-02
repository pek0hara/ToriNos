package com.nostr.torinos.badge

import com.nostr.torinos.account.AccountSigner
import com.nostr.torinos.crypto.isValidEvent
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.LocalSettingsStorage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock

@Serializable
internal data class BadgeSavedState(
    val latest: NostrEvent? = null,
    val pending: NostrEvent? = null,
    val pendingRelays: Set<String> = emptySet(),
    val succeededRelays: Set<String> = emptySet(),
    val conflict: Boolean = false,
)

internal data class BadgeManagementState(
    val ready: Boolean = false,
    val busy: Boolean = false,
    val latest: NostrEvent? = null,
    val awards: List<BadgeDisplayItem> = emptyList(),
    val message: String? = null,
    val incomplete: Boolean = false,
    val canLoadMore: Boolean = true,
)

internal data class BadgeDraft(val baselineId: String?, val source: NostrEvent?, val items: List<BadgeSelectionItem>)

internal class AccountBadgeStore(
    private val pubkey: String,
    private val sessionId: String,
    private val signer: AccountSigner,
    private val scope: CoroutineScope,
    private val repository: BadgeRepository,
    private val relays: () -> Set<String>,
    private val writableRelays: () -> Set<String>,
    private val ensureActive: () -> Unit,
    private val readStorage: suspend (String) -> String? = LocalSettingsStorage::getString,
    private val writeStorage: suspend (String, String?) -> Unit = LocalSettingsStorage::putString,
    private val validate: (NostrEvent) -> Boolean = ::isValidEvent,
    private val now: () -> Long = { Clock.System.now().epochSeconds },
) {
    private val mutex = Mutex()
    private val sendMutex = Mutex()
    private val awardsMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val key = "badge_selection_v1_$pubkey"
    private var saved = BadgeSavedState()
    private var initialized = false
    private var confirmedAbsent = false
    private var started = false
    private var job: Job? = null
    private var liveJob: Job? = null
    private var release: (() -> Unit)? = null
    private val received = linkedMapOf<String, NostrEvent>()
    private var boundary: Long? = null
    private var pageLimit = 100
    private val mutableState = MutableStateFlow(BadgeManagementState())
    val state: StateFlow<BadgeManagementState> = mutableState.asStateFlow()

    suspend fun initialize() = mutex.withLock {
        if (initialized) return@withLock
        ensureActive()
        val text = readStorage(key)
        if (text != null) {
            val restored = json.decodeFromString<BadgeSavedState>(text)
            require(listOfNotNull(restored.latest, restored.pending).all { it.pubkey == pubkey && it.isBadgeProfile() && validate(it) }) { "バッジの保存データを検証できませんでした" }
            saved = restored
            repository.ingest(listOfNotNull(saved.latest, saved.pending))
        }
        initialized = true
        publishState()
    }

    fun start() {
        if (started) return
        started = true
        release = repository.acquire(pubkey)
        job = scope.launch {
            try {
                initialize()
                refreshSelection()
                retryPending()
                while (isActive) { delay(30_000); retryPending() }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { showError(e) }
        }
        liveJob = scope.launch {
            try {
                initialize()
                repository.transport.live(pubkey, relays()) { event ->
                    acceptRemote(event)
                    repository.resolveProfiles(setOf(pubkey), relays())
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Foreground fetch/retry remains available if live subscription fails. */ }
        }
    }

    fun onForeground() { repository.onForeground(); scope.launch { try { refreshSelection(); retryPending() } catch (e: CancellationException) { throw e } catch (e: Exception) { showError(e) } } }

    private fun publishState(message: String? = syncMessage()) {
        val latest = effectiveLatest()
        mutableState.value = mutableState.value.copy(ready = initialized && (latest != null || confirmedAbsent), latest = latest, message = message)
    }

    private fun effectiveLatest(): NostrEvent? = selectBadgeProfile(listOfNotNull(saved.latest, saved.pending), pubkey)

    private fun syncMessage(): String? = when {
        saved.conflict -> "別の端末で表示バッジが更新されました。最新版を読み込んで再編集してください"
        saved.pending == null -> null
        saved.pendingRelays.isEmpty() && saved.succeededRelays.isEmpty() -> "送信先が未設定です"
        saved.succeededRelays.isNotEmpty() -> "一部のリレーに未同期"
        else -> "送信待ち"
    }

    suspend fun refreshSelection(): Boolean {
        initialize()
        ensureActive()
        val result = repository.refresh(setOf(pubkey), forceDependencies = true)
        val remote = selectBadgeProfile(result.events, pubkey)
        mutex.withLock {
            ensureActive()
            confirmedAbsent = result.complete && remote == null
            if (remote != null) acceptRemoteLocked(remote)
            publishState(if (result.complete) syncMessage() else "表示バッジの最新情報を取得できませんでした。再試行してください")
        }
        return result.complete
    }

    private suspend fun persist(next: BadgeSavedState) {
        ensureActive()
        writeStorage(key, json.encodeToString(BadgeSavedState.serializer(), next))
        ensureActive()
        saved = next
    }

    private suspend fun acceptRemoteLocked(event: NostrEvent) {
        val latest = effectiveLatest()
        if (latest != null && (selectBadgeProfile(listOf(latest, event), pubkey)?.id != event.id || latest.id == event.id)) return
        val conflict = saved.pending != null && saved.pending?.id != event.id
        persist(saved.copy(latest = event, conflict = saved.conflict || conflict))
        repository.ingest(listOf(event))
    }

    private suspend fun acceptRemote(event: NostrEvent) = mutex.withLock { ensureActive(); acceptRemoteLocked(event); publishState() }

    fun draft(): BadgeDraft {
        val latest = state.value.latest
        return BadgeDraft(latest?.id, latest, BadgeSelection.parse(latest?.tags.orEmpty()).items)
    }

    suspend fun save(draft: BadgeDraft): Boolean {
        mutableState.update { it.copy(busy = true) }
        try {
            if (!refreshSelection()) return false
            mutex.withLock {
                ensureActive()
                val latest = effectiveLatest()
                if (latest?.id != draft.baselineId) {
                    publishState("別の端末で更新されました。ドラフトを保持しています。最新版を読み込んで再編集してください")
                    return false
                }
                check(latest != null || confirmedAbsent) { "初回保存の前に最新情報を取得してください" }
                val source = draft.source
                val selection = BadgeSelection.parse(source?.tags.orEmpty())
                val added = draft.items.filter { it !in selection.items }
                check(added.all { item ->
                    val address = item.address
                    address?.kind == 30009 && repository.display(item, pubkey) != null
                }) { "授与を確認できないバッジは追加できません" }
                val timestamp = latest?.createdAt
                check(timestamp == null || timestamp <= now() + 300) { "未来時刻の表示リストと競合しています" }
                val event = signer.sign(
                    kind = 10008, content = source?.content.orEmpty(),
                    tags = selection.editedTags(draft.items, migrateLegacy = source?.kind == 30008),
                    createdAt = maxOf(now(), (timestamp ?: (now() - 1)) + 1),
                )
                ensureActive()
                val next = BadgeSavedState(latest = event, pending = event, pendingRelays = writableRelays())
                persist(next)
                repository.ingest(listOf(event))
                publishState()
            }
            // Local persistence completes the edit; network work belongs to the account, not the sheet.
            scope.launch { try { repository.resolveProfiles(setOf(pubkey), relays()); retryPending() } catch (e: CancellationException) { throw e } catch (e: Exception) { showError(e) } }
            return true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { showError(e); return false }
        finally { mutableState.update { it.copy(busy = false) } }
    }

    suspend fun retryPending() = sendMutex.withLock {
        initialize()
        if (mutex.withLock { saved.pending == null || saved.conflict }) return@withLock
        // Re-fetch before retry to avoid publishing an obsolete local list after remote changes.
        if (!refreshSelection()) return@withLock
        val pending = mutex.withLock {
            ensureActive()
            if (saved.conflict) return@withLock null
            val event = saved.pending ?: return@withLock null
            val targets = saved.pendingRelays.ifEmpty { if (saved.succeededRelays.isEmpty()) writableRelays() else emptySet() }
            event to targets
        } ?: return@withLock
        if (pending.second.isEmpty()) return@withLock
        val result = repository.transport.publish(pending.first, pending.second)
        mutex.withLock {
            ensureActive()
            // Both session lease and event identity guard late OK responses from previous saves.
            if (saved.pending?.id != pending.first.id || saved.conflict) return@withLock
            val remaining = pending.second - result.succeededRelays
            val next = saved.copy(pending = if (remaining.isEmpty()) null else pending.first,
                pendingRelays = remaining, succeededRelays = saved.succeededRelays + result.succeededRelays)
            persist(next)
            publishState(if (remaining.isEmpty()) "保存済み" else syncMessage())
        }
    }

    suspend fun loadAwards(reset: Boolean = true) = awardsMutex.withLock {
        mutableState.update { it.copy(busy = true) }
        try {
            initialize()
            if (reset) { received.clear(); boundary = null; pageLimit = 100 }
            val requestedLimit = pageLimit
            val result = repository.transport.fetch(listOf(NostrFilter(kinds = listOf(8), pTags = listOf(pubkey), until = boundary, limit = requestedLimit)), repository.relaysFor(setOf(pubkey), readMentions = true, base = relays()))
            ensureActive()
            result.events.forEach { received[it.id] = it }
            repository.ingest(result.events)
            val items = received.values.mapNotNull { event ->
                val address = event.tags.firstOrNull { it.firstOrNull() == "a" }?.getOrNull(1)?.let(BadgeAddress::parse) ?: return@mapNotNull null
                if (!isBadgeAwardFor(event, address, pubkey)) return@mapNotNull null
                BadgeSelectionItem(listOf(listOf("a", address.value), listOf("e", event.id)))
            }
            repository.resolveItems(items)
            val displays = items.mapNotNull { repository.display(it, pubkey) }.distinctBy { it.address }
            // `limit` applies per relay: only relays that filled the page may hold older awards, and the
            // next page must start at the newest of their oldest times so no relay skips a range.
            // Relays may cap a raised limit, so a full base page (100) also counts as "may have more".
            val next = result.pages.values.filter { it.count >= minOf(requestedLimit, 100) }.maxOfOrNull { it.oldest }
            if (next != null) {
                if (next == boundary) pageLimit = (pageLimit * 2).coerceAtMost(2_000)
                else { boundary = next; pageLimit = 100 }
            }
            mutableState.update { it.copy(awards = displays, incomplete = !result.complete || pageLimit == 2_000,
                canLoadMore = !result.complete || next != null, message = if (!result.complete) "受け取ったバッジを一部取得できませんでした" else syncMessage()) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { showError(e) }
        finally { mutableState.update { it.copy(busy = false) } }
    }

    private fun showError(error: Exception) { mutableState.update { it.copy(message = error.message ?: "バッジ処理に失敗しました") } }
    fun close() { job?.cancel(); liveJob?.cancel(); release?.invoke(); repository.close() }
}
