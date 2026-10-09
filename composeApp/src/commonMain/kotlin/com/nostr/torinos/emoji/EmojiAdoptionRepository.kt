package com.nostr.torinos.emoji

import com.nostr.torinos.crypto.sha256
import com.nostr.torinos.crypto.toHex
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.time.Clock

internal data class EmojiAdoptionState(
    val relayUrl: String? = null,
    val follows: Set<String> = emptySet(),
    /** 公開鍵 → 最新の kind 10030。応答が無かった人は前回の内容を残す。 */
    val latest: Map<String, NostrEvent> = emptyMap(),
    val isLoading: Boolean = false,
    /** 一部のリレー応答が欠けた。次回は取り直す。 */
    val isPartial: Boolean = false,
    /** 利用者が取得を止めた。次回は取り直す。 */
    val wasStopped: Boolean = false,
) {
    val counts: Map<EmojiSetAddress, Int> by lazy { latest.values.flatMap(::emojiSetReferences).groupingBy { it }.eachCount() }

    /** セットごとの、そのセットを kind 10030 に載せているフォロー先の公開鍵。 */
    val users: Map<EmojiSetAddress, List<String>> by lazy {
        val result = mutableMapOf<EmojiSetAddress, MutableList<String>>()
        latest.forEach { (pubkey, event) ->
            emojiSetReferences(event).forEach { address -> result.getOrPut(address) { mutableListOf() } += pubkey }
        }
        result
    }
}

internal fun emojiSetReferences(event: NostrEvent): Set<EmojiSetAddress> = event.tags.asSequence()
    .filter { it.firstOrNull() == "a" }
    .mapNotNull { it.getOrNull(1)?.let(EmojiSetAddress::parse) }
    .distinct().take(1_000).toSet()

/** 生のイベント列から、公開鍵ごとに最新の kind 10030 だけを数える。 */
internal fun emojiAdoptionCounts(events: Iterable<NostrEvent>): Map<EmojiSetAddress, Int> =
    EmojiAdoptionState(latest = latestPreferenceLists(emptyMap(), events)).counts

internal fun EmojiAdoptionState.receive(events: List<NostrEvent>): EmojiAdoptionState =
    copy(latest = latestPreferenceLists(latest, events.filter { it.pubkey in follows }))

private fun latestPreferenceLists(
    previous: Map<String, NostrEvent>,
    events: Iterable<NostrEvent>,
): Map<String, NostrEvent> {
    val next = previous.toMutableMap()
    events.filter { it.kind == EmojiPreferenceSync.KIND_EMOJI_PREFERENCES }.forEach { event ->
        val current = next[event.pubkey]
        if (current == null || event.isNewerThan(current)) next[event.pubkey] = event
    }
    return next
}

/** AccountSessionが所有する。他アカウントへ登録人数・公開リストを持ち越さない。 */
internal class EmojiAdoptionRepository(
    private val ownPubkey: String,
    sessionId: String,
    private val scope: CoroutineScope,
    private val transport: EmojiDiscoveryTransport = EmojiDiscoveryTransport(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val requestOwner = sha256(sessionId.encodeToByteArray()).toHex().take(16)
    private val _state = MutableStateFlow(EmojiAdoptionState())
    val state = _state.asStateFlow()
    private var job: Job? = null
    private var generation = 0L
    /** 今の集計を始めた時刻。ここから [STALE_AFTER_MS] の間は、応答し終えた人を取り直さない。 */
    private var roundStartedAt = 0L
    /** 今の集計で、リレーが最後まで応答した公開鍵。中断や時間切れの分は含めない。 */
    private var fetchedAuthors = emptySet<String>()

    /**
     * 同じリレーで集計が新しいうちは、まだ応答を得ていない人だけを取る。
     * 途中で止めた・応答が欠けた分の再開や、フォローの追加ではその差分だけを取り、外した人は集計から除く。
     */
    fun start(relay: String, follows: Set<String>, force: Boolean = false) {
        val targets = follows - ownPubkey
        val current = _state.value
        val sameRelay = current.relayUrl == relay
        val fresh = !force && sameRelay && now() - roundStartedAt < STALE_AFTER_MS
        val pending = if (fresh) targets - fetchedAuthors else targets
        if (fresh && current.follows == targets && (job?.isActive == true || pending.isEmpty())) return
        job?.cancel()
        val token = ++generation
        if (!fresh) {
            roundStartedAt = now()
            fetchedAuthors = emptySet()
        }
        // 取り直す間も、応答が無かった人は前回の内容を残す。
        _state.value = if (sameRelay) current.copy(
            follows = targets, latest = current.latest.filterKeys { it in targets },
            isLoading = pending.isNotEmpty(), isPartial = false, wasStopped = false,
        ) else EmojiAdoptionState(relayUrl = relay, follows = targets, isLoading = pending.isNotEmpty())
        if (pending.isEmpty()) return
        job = scope.launch(dispatcher) {
            val semaphore = Semaphore(2)
            coroutineScope {
                pending.sorted().chunked(50).forEachIndexed { index, batch ->
                    launch {
                        semaphore.withPermit {
                            val result = transport.fetch("emoji-users-$requestOwner-$token-$index", relay,
                                listOf(preferenceListFilter(batch)))
                            if (token != generation) return@withPermit
                            _state.update { it.receive(result.events).copy(isPartial = it.isPartial || !result.complete) }
                            var complete = result.complete
                            val received = result.events.map { it.pubkey }.toSet()
                            val missing = batch.filter { it !in received }
                            if (result.events.size >= batch.size && missing.isNotEmpty()) {
                                missing.chunked(10).forEachIndexed { retry, group ->
                                    val extra = transport.fetch("emoji-retry-$requestOwner-$token-$index-$retry", relay,
                                        listOf(preferenceListFilter(group)))
                                    if (token != generation) return@withPermit
                                    complete = complete && extra.complete
                                    _state.update {
                                        it.receive(extra.events).copy(isPartial = it.isPartial || !extra.complete)
                                    }
                                }
                            }
                            if (complete) fetchedAuthors = fetchedAuthors + batch
                        }
                    }
                }
            }
            if (token == generation) _state.update { it.copy(isLoading = false) }
        }
    }

    private var users = 0

    /** 画面ごとに取得し、最後の画面が離れたときだけ取得を止める。 */
    fun acquire(): () -> Unit {
        users++
        var released = false
        return {
            if (!released) {
                released = true
                users = (users - 1).coerceAtLeast(0)
                if (users == 0) stop()
            }
        }
    }

    fun stop() {
        ++generation
        job?.cancel()
        if (_state.value.isLoading) _state.update { it.copy(isLoading = false, wasStopped = true) }
    }

    fun close() {
        stop()
        roundStartedAt = 0L
        fetchedAuthors = emptySet()
        _state.value = EmojiAdoptionState()
    }
    private fun preferenceListFilter(authors: List<String>) =
        NostrFilter(kinds = listOf(EmojiPreferenceSync.KIND_EMOJI_PREFERENCES), authors = authors, limit = authors.size)

    private companion object {
        const val STALE_AFTER_MS = 300_000L
    }
}
