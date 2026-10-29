package com.nostr.torinos.zap

import com.nostr.torinos.engagement.EngagementStore
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.network.ProfileRepository
import com.nostr.torinos.util.BoundedLruCache
import com.nostr.torinos.util.SynchronousLock
import com.nostr.torinos.util.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.time.Clock

/**
 * 作者ごとの、受け取り先の LNURL サーバーの `nostrPubkey`（レシートに署名する鍵）。
 * レシートの `pubkey` をこれと照合する（NIP-57 Appendix F）。レシートが届いた作者の分だけ、同時 [concurrency] 本で取りに行く。
 * 分かった鍵は、そのときの受け取り先の URL とともに [ttlMillis] のあいだ覚える。受け取り先が変わったら取り直す。
 */
internal class ZapperDirectory(
    private val scope: CoroutineScope,
    private val endpoints: LnurlPayRepository,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val ttlMillis: Long = 24 * 60 * 60 * 1_000L,
    concurrency: Int = 2,
) {
    private data class Known(val url: String, val nostrPubkey: String?, val at: Long)

    private val lock = SynchronousLock()
    private val known = MutableStateFlow<Map<String, Known>>(emptyMap())
    private val requested = mutableSetOf<String>()
    private val permits = Semaphore(concurrency)

    /** 作者の受け取り先の鍵が分かった・変わったときに流れる。 */
    fun observe(author: String): Flow<String?> = known.map { it[author]?.nostrPubkey }.distinctUntilChanged()

    /**
     * 受け取り先 [address] の鍵。覚えていなければ取りに行き、いまは null を返す（分かったら [observe] に流れる）。
     * 受け取り先が分からない作者（[address] が null）は数えない。
     */
    fun zapperFor(author: String, address: ZapRecipientAddress?): String? {
        if (address == null) return null
        val entry = known.value[author]
        if (entry != null && entry.url == address.url && now() - entry.at < ttlMillis) return entry.nostrPubkey
        val key = "$author ${address.url}"
        if (lock.withLock { !requested.add(key) }) return entry?.takeIf { it.url == address.url }?.nostrPubkey
        scope.launch {
            try {
                val endpoint = permits.withPermit { endpoints.get(address.url) }
                // 2本まで並行して書くので、読み書きを分けると片方の結果が消える。
                known.update { it + (author to Known(address.url, endpoint.nostrPubkey, now())) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 失敗は覚えない。取得の間隔は LnurlPayRepository が守る。
            } finally {
                lock.withLock { requested.remove(key) }
            }
        }
        return entry?.takeIf { it.url == address.url }?.nostrPubkey
    }
}

/**
 * 投稿ごとの Zap の合計。付随情報ストアに集まったレシートを検証して数える。
 * 署名の検証は重いので、読んだ結果をレシートIDごとに覚える。
 */
internal class ZapTally(
    private val store: EngagementStore,
    private val directory: ZapperDirectory,
    private val profileOf: (String) -> NostrProfile?,
    private val profileChanges: (String) -> Flow<NostrProfile?>,
    /** 署名の検証を行う場所。 */
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    maxParsed: Int = 5_000,
) {
    private val lock = SynchronousLock()
    private val parsed = BoundedLruCache<String, Result<ZapReceipt?>>(maxParsed)

    private fun read(receipt: NostrEvent): ZapReceipt? {
        lock.withLock { parsed[receipt.id] }?.let { return it.getOrNull() }
        val result = runCatching { parseZapReceipt(receipt) }
        lock.withLock { parsed[receipt.id] = result }
        return result.getOrNull()
    }

    fun current(eventId: String, authorPubkey: String): ZapTotal {
        val receipts = store.snapshot(eventId)?.zaps?.values.orEmpty()
        if (receipts.isEmpty()) return ZapTotal.Empty
        val candidates = receipts.mapNotNull(::read)
            .filter { it.targetEventId == eventId && it.recipientPubkey == authorPubkey }
        if (candidates.isEmpty()) return ZapTotal.Empty
        val zapper = directory.zapperFor(authorPubkey, profileOf(authorPubkey)?.zapRecipientAddress())
        return summarizeZaps(candidates, eventId, authorPubkey, zapper)
    }

    /** レシートの到着・作者のプロフィールの更新・受け取り先の鍵の判明のたびに数え直す。 */
    fun observe(eventId: String, authorPubkey: String): Flow<ZapTotal> = channelFlow {
        val recount = Channel<Unit>(Channel.CONFLATED)
        recount.trySend(Unit)
        launch { store.changes.collect { if (eventId in it) recount.trySend(Unit) } }
        launch { profileChanges(authorPubkey).collect { recount.trySend(Unit) } }
        launch { directory.observe(authorPubkey).collect { recount.trySend(Unit) } }
        for (unit in recount) send(current(eventId, authorPubkey))
    }.distinctUntilChanged().flowOn(dispatcher)

    companion object {
        val shared: ZapTally by lazy {
            ZapTally(
                store = EngagementStore.shared,
                directory = ZapperDirectory(CoroutineScope(SupervisorJob() + Dispatchers.Default), LnurlHttp.endpoints),
                profileOf = ProfileRepository::getCached,
                profileChanges = ProfileRepository::observe,
            )
        }
    }
}
