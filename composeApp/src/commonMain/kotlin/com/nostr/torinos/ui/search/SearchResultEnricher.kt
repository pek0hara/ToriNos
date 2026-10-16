package com.nostr.torinos.ui.search

import com.nostr.torinos.model.COMMENT_EVENT_KIND
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.extractNpubReferences
import com.nostr.torinos.network.SubscriptionBehavior
import com.nostr.torinos.network.SubscriptionSignal
import com.nostr.torinos.network.SubscriptionSpec
import com.nostr.torinos.ui.SafeCoroutineLauncher
import com.nostr.torinos.util.logException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext

/**
 * 検索結果の投稿に付随するプロフィールと、返信・リアクション・リポストを取得する。
 *
 * - プロフィールは表示に使うpubkeyを「参照済み」、取得依頼前のものを「未送信」として分けて持つ。
 *   停止で未送信のまま残った分は再開時に依頼する。キャッシュ監視は表示中に1本だけ維持する。
 * - 付随情報は新しく追加された投稿だけを有限バッチで取得し、取得中に停止したバッチは再開時に取り直す。
 *   再取得したイベントは [SearchEngagementAccumulator] の索引で重複を除く。
 * - 待機は最初の追加から固定時間で締め切り、連続して届いても延長しない。
 *
 * 呼び出しはすべて同じスレッド（メインまたはテストのディスパッチャ）から行う。
 */
internal class SearchResultEnricher(
    scope: CoroutineScope,
    private val gateway: SearchGateway,
    private val config: SearchConfig,
    private val nowEpochSeconds: () -> Long,
    private val nextSubscriptionId: () -> String,
    private val onChanged: () -> Unit,
    sessionId: String? = null,
    ownPubkey: String? = null,
) {
    private val launcher = SafeCoroutineLauncher(scope, "SearchResultEnricher")
    private val accumulator = SearchEngagementView(
        config.maxTrackedEngagementTargets,
        if (gateway === RepositorySearchGateway) com.nostr.torinos.engagement.EngagementStore.shared else com.nostr.torinos.engagement.EngagementStore(),
        sessionId, ownPubkey,
    )

    private val referencedPubkeys = linkedSetOf<String>()
    private val queuedPubkeys = linkedSetOf<String>()
    private var profiles: Map<String, NostrProfile> = emptyMap()

    private var pendingTargets = linkedSetOf<String>()
    private var inFlightTargets: List<String> = emptyList()

    private var active = false
    private var watchJob: Job? = null
    private var engagementWatchJob: Job? = null
    private var pendingWatchJob: Job? = null
    private var profileWorker: Job? = null
    private var engagementWorker: Job? = null

    val currentProfiles: Map<String, NostrProfile> get() = profiles

    fun engagementSnapshot(): Map<String, SearchEngagement> = accumulator.snapshot()

    /** 新しい検索のために、取得中の処理と保持している情報をすべて捨てる。投稿の取得を始めるまで [resume] しない。 */
    fun reset() {
        active = false
        cancelJobs()
        referencedPubkeys.clear()
        queuedPubkeys.clear()
        profiles = emptyMap()
        pendingTargets = linkedSetOf()
        inFlightTargets = emptyList()
        accumulator.clear()
    }

    /** 結果に新しく加わった投稿の作者・メンション先・付随情報を取得対象にする。 */
    fun onPostAdded(event: NostrEvent) {
        referencePubkey(event.pubkey)
        extractNpubReferences(event.content).forEach { referencePubkey(it.pubkey) }
        if (accumulator.track(event.id)) {
            pendingTargets += event.id
            ensureEngagementWorker()
        }
    }

    /** 画面が非表示になった。未送信と取得中だった分は再開まで保持する。 */
    fun stop() {
        if (!active) return
        active = false
        cancelJobs()
        if (inFlightTargets.isNotEmpty()) {
            pendingTargets = linkedSetOf<String>().apply {
                addAll(inFlightTargets)
                addAll(pendingTargets)
            }
            inFlightTargets = emptyList()
        }
    }

    /** 投稿の取得を始めた、または画面へ戻った。キャッシュの最新値を読み直し、停止で残った取得を再開する。 */
    fun resume() {
        if (active) return
        active = true
        startWatching()
        ensureProfileWorker()
        ensureEngagementWorker()
    }

    private fun cancelJobs() {
        watchJob?.cancel()
        watchJob = null
        engagementWatchJob?.cancel()
        engagementWatchJob = null
        pendingWatchJob?.cancel(); pendingWatchJob = null
        profileWorker?.cancel()
        profileWorker = null
        engagementWorker?.cancel()
        engagementWorker = null
    }

    private fun referencePubkey(pubkey: String) {
        if (!referencedPubkeys.add(pubkey)) return
        gateway.cachedProfiles(listOf(pubkey))[pubkey]?.let { profile ->
            profiles = profiles + (pubkey to profile)
            onChanged()
        }
        queuedPubkeys += pubkey
        ensureProfileWorker()
    }

    private fun startWatching() {
        engagementWatchJob?.cancel()
        engagementWatchJob = launcher.launch {
            accumulator.store.changes.collect { changed ->
                if (changed.any(accumulator::isTracked)) onChanged()
            }
        }
        pendingWatchJob?.cancel()
        pendingWatchJob = launcher.launch { accumulator.pending.changes.collect { changed ->
            if (changed.any(accumulator::isTracked)) onChanged()
        } }
        // 停止中に変わった集計も再公開する。
        onChanged()
        watchJob?.cancel()
        watchJob = launcher.launch {
            // 監視開始時に空集合が届くため、停止中に変わった分もここで読み直される。
            gateway.profileChanges().collect { changed ->
                val affected = if (changed.isEmpty()) referencedPubkeys.toSet() else changed.intersect(referencedPubkeys)
                if (affected.isEmpty()) return@collect
                val updated = profiles - affected + gateway.cachedProfiles(affected)
                if (updated != profiles) {
                    profiles = updated
                    onChanged()
                }
            }
        }
    }

    private fun ensureProfileWorker() {
        if (!active || queuedPubkeys.isEmpty() || profileWorker?.isActive == true) return
        profileWorker = launcher.launch {
            while (queuedPubkeys.isNotEmpty()) {
                delay(config.profileBatchMillis)
                val batch = queuedPubkeys.toSet()
                gateway.requestProfiles(batch, config.profileMaxAgeMillis)
                queuedPubkeys.removeAll(batch)
            }
        }
    }

    private fun ensureEngagementWorker() {
        if (!active || pendingTargets.isEmpty() || engagementWorker?.isActive == true) return
        engagementWorker = launcher.launch {
            delay(config.engagementBatchMillis)
            while (true) {
                val batch = pendingTargets.filter(accumulator::isTracked)
                pendingTargets = linkedSetOf()
                if (batch.isEmpty()) break
                inFlightTargets = batch
                try {
                    fetchEngagements(batch)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    // 失敗した対象はそこまでの集計を表示し、再取得しない（取得範囲内の値という要件どおり）。
                    logException("SearchResultEnricher", error, "engagement fetch failed")
                }
                inFlightTargets = emptyList()
            }
        }
    }

    private suspend fun fetchEngagements(targets: List<String>) {
        val since = nowEpochSeconds() - config.engagementLookbackSeconds
        val session = gateway.openSubscription(
            SubscriptionSpec(
                id = nextSubscriptionId(),
                filters = engagementFilters(targets, since, config.engagementFilterLimit),
                behavior = SubscriptionBehavior.Fetch(config.engagementTimeoutMillis),
                replayCachedEvents = false,
            ),
        )
        var received = 0
        try {
            session.signals.firstOrNull { signal ->
                when (signal) {
                    is SubscriptionSignal.Event -> {
                        accumulator.apply(signal.event, signal.relayUrl).forEach(::referencePubkey)
                        onChanged()
                        received++
                        // 予算に達したら残りは取得しない。対象は取得済みとして扱い、再取得しない。
                        received >= config.engagementBatchEventLimit
                    }
                    is SubscriptionSignal.FetchCompleted -> true
                    else -> false
                }
            }
        } finally {
            withContext(NonCancellable) { session.close() }
        }
    }
}

internal fun engagementFilters(eventIds: List<String>, since: Long, limit: Int): List<NostrFilter> = listOf(
    NostrFilter(kinds = listOf(7), eTags = eventIds, since = since, limit = limit),
    NostrFilter(kinds = listOf(1), eTags = eventIds, since = since, limit = limit),
    NostrFilter(
        kinds = listOf(COMMENT_EVENT_KIND),
        rootKindTags = listOf("1"),
        eTags = eventIds,
        since = since,
        limit = limit,
    ),
    NostrFilter(
        kinds = listOf(COMMENT_EVENT_KIND),
        rootKindTags = listOf("1"),
        rootEventTags = eventIds,
        since = since,
        limit = limit,
    ),
    NostrFilter(kinds = listOf(6), eTags = eventIds, since = since, limit = limit),
    NostrFilter(kinds = listOf(1), qTags = eventIds, since = since, limit = limit),
)
