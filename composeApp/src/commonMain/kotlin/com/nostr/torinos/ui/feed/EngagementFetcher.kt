package com.nostr.torinos.ui.feed

import com.nostr.torinos.engagement.EngagementStore
import com.nostr.torinos.model.COMMENT_EVENT_KIND
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.RelayLimits
import com.nostr.torinos.network.RelayOutcome
import com.nostr.torinos.network.RelayTarget
import com.nostr.torinos.network.RetryDisposition
import com.nostr.torinos.network.SubscriptionBehavior
import com.nostr.torinos.network.SubscriptionSession
import com.nostr.torinos.network.SubscriptionSignal
import com.nostr.torinos.network.SubscriptionSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * 投稿に付随するリアクション・返信・リポスト・引用を、リレーごと・区分ごとに取得する。
 *
 * フィードやポスト詳細など複数の画面（利用者）で共有する。利用者ごとに追跡する投稿を持ち、
 * どの利用者も追跡しなくなった投稿の取得進捗だけを捨てる。同じ投稿を複数の画面が表示しても、
 * リレーへの問い合わせは1回になる。利用者が1つでも動いている（[activate]）あいだ購読する。
 *
 * 受信したイベントは付随情報ストアへ入れ、動いている利用者へ知らせる。件数は持たない。
 */
internal class EngagementFetcher(
    private val scope: CoroutineScope,
    private val subscriptions: FeedSubscriptionGateway,
    private val relayTarget: RelayTarget,
    private val store: EngagementStore,
    /** ライブ購読の購読IDの接頭辞。開始ごとに連番を付ける（履歴取得はさらに連番を付ける）。 */
    private val subscriptionIdPrefix: String,
    private val log: (() -> String) -> Unit = {},
) {
    private class Owner(
        /** ライブ購読を新しい投稿に絞るための並び時刻。分からない投稿は新着とみなす。 */
        val sortTimeOf: (String) -> Long?,
        val onEvent: (NostrEvent) -> Unit,
    )

    private val owners = linkedMapOf<Any, Owner>()
    private val ownerTargets = mutableMapOf<Any, LinkedHashSet<String>>()
    private val activeOwners = mutableSetOf<Any>()
    private var generation = 0
    private var subscriptionId: String? = null
    private var started = false
    private val jobs = mutableListOf<Job>()
    private var engagementSession: SubscriptionSession? = null
    // ライブ購読へ最後に送った監視ID。since は呼び出しごとに進むため、IDが同じなら REQ を再送しない。
    private var engagementLiveEventIds: List<String>? = null
    private var engagementLiveRelays: Set<String>? = null
    private var engagementResubscribeRequested = false
    private var liveEngagementUpdateRequested = false
    private var liveEngagementUpdateJob: Job? = null
    private val engagementHistorySessions = mutableMapOf<String, SubscriptionSession>()
    private val watchedEventIds = linkedSetOf<String>()
    private val engagementHistoryStates = mutableMapOf<String, EngagementHistoryState>()
    private val engagementCompletedPartitions =
        mutableMapOf<String, MutableMap<String, MutableSet<EngagementPartition>>>()
    /**
     * リレーが件数上限で打ち切った可能性がある取得の続き。投稿ID→リレー→区分ごとに、
     * 次に取りにいく until を持つ。値があるあいだその区分は取得済みにしない。
     */
    private val engagementHistoryCursors =
        mutableMapOf<String, MutableMap<String, MutableMap<EngagementPartition, Long>>>()
    private val isolatedEngagementRelays = mutableSetOf<String>()
    private val engagementFailureCounts = mutableMapOf<String, Int>()
    private val engagementEventMutex = Mutex()
    private val engagementSubscriptionMutex = Mutex()
    private var lastEngagementTargetRelays: Set<String>? = null
    private var engagementBatchJob: Job? = null
    private val engagementRetryJobs = mutableMapOf<String, Job>()
    private var engagementResumeSince: Long? = null
    private var nextEngagementHistoryRequestId = 0L

    fun register(owner: Any, sortTimeOf: (String) -> Long?, onEvent: (NostrEvent) -> Unit) {
        owners[owner] = Owner(sortTimeOf, onEvent)
    }

    /** [owner] が購読を必要とし始めた。最初の利用者で購読を始める。 */
    fun activate(owner: Any) {
        if (!activeOwners.add(owner) || activeOwners.size > 1) return
        subscriptionId = "$subscriptionIdPrefix-${++generation}"
        started = true
    }

    /** [owner] が購読を必要としなくなった。最後の利用者で購読を止める（取得の進捗は残す）。 */
    fun deactivate(owner: Any) {
        if (!activeOwners.remove(owner) || activeOwners.isNotEmpty()) return
        stop()
    }

    /** [owner] が [eventId] の付随情報を必要とする。[owner] にとって新しければ true。 */
    fun track(owner: Any, eventId: String): Boolean {
        if (!ownerTargets.getOrPut(owner) { linkedSetOf() }.add(eventId)) return false
        track(eventId)
        return true
    }

    /** [owner] が [eventId] を必要としなくなった。どの利用者も必要としなければ進捗を捨てる。 */
    fun forget(owner: Any, eventId: String) {
        if (ownerTargets[owner]?.remove(eventId) != true) return
        if (ownerTargets.values.none { eventId in it }) forget(eventId)
    }

    /** [owner] の追跡をすべてやめる。 */
    fun reset(owner: Any) {
        ownerTargets[owner]?.toList()?.forEach { forget(owner, it) }
        if (ownerTargets.values.all { it.isEmpty() }) reset()
    }

    fun release(owner: Any) {
        reset(owner)
        deactivate(owner)
        owners.remove(owner)
        ownerTargets.remove(owner)
    }

    fun isTracked(eventId: String): Boolean = eventId in engagementHistoryStates

    fun isTrackedBy(owner: Any, eventId: String): Boolean = ownerTargets[owner]?.contains(eventId) == true

    fun trackedBy(owner: Any): Set<String> = ownerTargets[owner].orEmpty()

    private fun sortTimeOf(eventId: String): Long? = ownerTargets.entries
        .filter { (_, targets) -> eventId in targets }
        .maxOfOrNull { (owner, _) -> owners[owner]?.sortTimeOf?.invoke(eventId) ?: Long.MAX_VALUE }

    private fun onEvent(event: NostrEvent, relayUrl: String?) {
        store.observe(event, relayUrl)
        activeOwners.mapNotNull(owners::get).forEach { it.onEvent(event) }
    }

    /** 購読を止める。取得の進捗は残し、再開時に続きから取る。 */
    private fun stop() {
        if (!started) return
        started = false
        if (engagementSession != null) {
            engagementResumeSince = (
                Clock.System.now().epochSeconds - ENGAGEMENT_LIVE_OVERLAP_SECONDS
            ).coerceAtLeast(0L)
        }
        engagementHistoryStates.keys.toList().forEach { eventId ->
            if (engagementHistoryStates[eventId] == EngagementHistoryState.InFlight) {
                engagementHistoryStates[eventId] = EngagementHistoryState.Partial
            }
        }
        subscriptionId = null
        jobs.forEach { it.cancel() }
        jobs.clear()
        engagementBatchJob?.cancel()
        engagementBatchJob = null
        engagementRetryJobs.values.forEach(Job::cancel)
        engagementRetryJobs.clear()
        val sessionsToClose = listOfNotNull(engagementSession) + engagementHistorySessions.values
        engagementSession = null
        engagementLiveEventIds = null
        engagementLiveRelays = null
        liveEngagementUpdateJob?.cancel()
        liveEngagementUpdateJob = null
        liveEngagementUpdateRequested = false
        engagementResubscribeRequested = false
        engagementHistorySessions.clear()
        if (sessionsToClose.isNotEmpty()) {
            scope.launch { sessionsToClose.forEach { it.close() } }
        }
    }

    /** 追跡と取得の進捗をすべて捨てる。 */
    private fun reset() {
        watchedEventIds.clear()
        engagementHistoryStates.clear()
        engagementCompletedPartitions.clear()
        engagementHistoryCursors.clear()
        isolatedEngagementRelays.clear()
        engagementFailureCounts.clear()
        lastEngagementTargetRelays = null
        engagementResumeSince = null
    }

    /** 取得先のリレーが変わったとき。外れたリレーの進捗を捨て、増えたリレーから取り直す。 */
    suspend fun onTargetRelaysChanged(targetRelays: Set<String>) {
        val previousRelays = lastEngagementTargetRelays
        lastEngagementTargetRelays = targetRelays
        if (previousRelays == null || previousRelays == targetRelays) return
        val removedRelays = previousRelays - targetRelays
        if (removedRelays.isNotEmpty()) {
            engagementCompletedPartitions.values.forEach { completedByRelay ->
                removedRelays.forEach(completedByRelay::remove)
            }
            engagementHistoryCursors.values.forEach { cursorsByRelay ->
                removedRelays.forEach(cursorsByRelay::remove)
            }
            isolatedEngagementRelays.removeAll(removedRelays)
            removedRelays.forEach { relayUrl ->
                engagementFailureCounts.remove(relayUrl)
                engagementRetryJobs.remove(relayUrl)?.cancel()
            }
        }
        if (started && watchedEventIds.isNotEmpty()) resubscribe()
    }

    suspend fun resubscribe(retryPartialHistory: Boolean = false) {
        engagementSubscriptionMutex.withLock {
            resubscribeEngagementLocked(retryPartialHistory)
        }
    }

    private suspend fun resubscribeEngagementLocked(retryPartialHistory: Boolean) {
        val subId = subscriptionId ?: return
        if (watchedEventIds.isEmpty()) return
        val targetRelays = subscriptions.targetRelayUrls(relayTarget)
        val previousRelays = lastEngagementTargetRelays
        lastEngagementTargetRelays = targetRelays
        if (previousRelays != null) {
            val removedRelays = previousRelays - targetRelays
            if (removedRelays.isNotEmpty()) {
                engagementCompletedPartitions.values.forEach { completedByRelay ->
                    removedRelays.forEach(completedByRelay::remove)
                }
                engagementHistoryCursors.values.forEach { cursorsByRelay ->
                    removedRelays.forEach(cursorsByRelay::remove)
                }
            }
        }
        if (retryPartialHistory) {
            engagementRetryJobs.values.forEach(Job::cancel)
            engagementRetryJobs.clear()
            engagementFailureCounts.clear()
            engagementHistoryStates.keys.toList().forEach { eventId ->
                if (engagementHistoryStates[eventId] == EngagementHistoryState.Partial) {
                    engagementHistoryStates[eventId] = EngagementHistoryState.Pending
                }
            }
        }
        val ids = watchedEventIds.toList()
        val cutoff = Clock.System.now().epochSeconds
        // ライブ購読は新しい投稿に絞る。既存分の取得は履歴フェッチが担うため、スクロールで古い投稿が
        // 増えてもライブ側のREQは変わらない(全件を都度送り直すとREQが肥大化する)。
        val liveIds = liveEngagementEventIds(ids)
        val existing = engagementSession
        if (existing != null) {
            // 監視IDも取得先も同じなら since が違うだけの再送になる。変わっていればまとめて更新する。
            if (liveIds.toSet() != engagementLiveEventIds?.toSet() || targetRelays != engagementLiveRelays) {
                requestLiveEngagementUpdate()
            }
        } else {
            // 停止前に配信していた場合は、止まっていた間も含めて取り直す。
            val liveSince = engagementResumeSince
                ?: (cutoff - ENGAGEMENT_LIVE_OVERLAP_SECONDS).coerceAtLeast(0L)
            engagementResumeSince = null
            val filters = engagementFilters(liveIds, since = liveSince)
            engagementLiveEventIds = liveIds
            engagementLiveRelays = targetRelays
            log { "live open ids=${liveIds.size} since=$liveSince relays=${targetRelays.size}" }
            val session = subscriptions.open(
                SubscriptionSpec(
                    id = subId,
                    filters = filters,
                    target = relayTarget,
                    behavior = SubscriptionBehavior.Live,
                ),
            )
            engagementSession = session
            jobs += scope.launch {
                session.signals.collect { signal ->
                    if (engagementSession !== session) return@collect
                    if (signal is SubscriptionSignal.Event) {
                        engagementEventMutex.withLock { onEvent(signal.event, signal.relayUrl) }
                    }
                }
            }
        }

        if (targetRelays.isEmpty()) {
            // 起動直後は RelayStore の読み込みと Repository への反映に時間差がある。
            // 取得先が未確定な状態を「全リレー取得済み」とみなさず、確定通知を待つ。
            engagementHistoryStates.keys.toList().forEach { eventId ->
                if (engagementHistoryStates[eventId] != EngagementHistoryState.InFlight) {
                    engagementHistoryStates[eventId] = EngagementHistoryState.Pending
                }
            }
            return
        }
        // Complete を事前に除外すると、targetRelaysが後から広がったときに
        // 「古いリレー集合では完了済みだが新しいリレーではまだ」というケースを
        // updateEngagementHistoryState() が再評価できなくなる。全件を渡す。
        val backfillIds = engagementHistoryStates.keys.toList()
        backfillIds.forEach { eventId -> updateEngagementHistoryState(eventId, targetRelays) }
        val workByRelay = targetRelays.sorted().mapNotNull { relayUrl ->
            if (relayUrl in engagementHistorySessions) return@mapNotNull null
            if (engagementRetryJobs[relayUrl]?.isActive == true) return@mapNotNull null
            if ((engagementFailureCounts[relayUrl] ?: 0) > MAX_ENGAGEMENT_HISTORY_RETRIES) {
                return@mapNotNull null
            }
            nextEngagementHistoryWork(backfillIds, setOf(relayUrl))
        }
        workByRelay.forEach { work ->
            startEngagementHistoryFetch(subId = subId, work = work, until = cutoff)
        }
    }

    private suspend fun startEngagementHistoryFetch(
        subId: String,
        work: EngagementHistoryWork,
        until: Long,
    ) {
        val eventIds = work.eventIds
        // リレーが公開している max_limit より大きく求めても、返るのは max_limit 件まで。
        val maxLimit = RelayLimits.maxLimit(work.relayUrl)
        val requestLimit = maxLimit?.let { minOf(it, ENGAGEMENT_HISTORY_PAGE_LIMIT) } ?: ENGAGEMENT_HISTORY_PAGE_LIMIT
        // 上限が分かっていれば、それに届いたときだけ続きを取る。分からなければ、リレー側の既定の上限が
        // 求めた件数より小さい可能性があるため、控えめなしきい値で続きを取る。
        val pageThreshold = if (maxLimit != null) requestLimit else ENGAGEMENT_HISTORY_PAGE_THRESHOLD
        log {
            "history start ${work.relayUrl} ids=${eventIds.size} partitions=${work.partitions} " +
                "until=${work.until?.let { "$it(続き)" } ?: "$until(最新)"}"
        }
        eventIds.forEach { engagementHistoryStates[it] = EngagementHistoryState.InFlight }
        val session = try {
            subscriptions.open(
                SubscriptionSpec(
                    id = "$subId-history-${++nextEngagementHistoryRequestId}",
                    filters = work.partitions.map {
                        it.filter(eventIds, until = work.until ?: until, limit = requestLimit)
                    },
                    target = RelayTarget.Explicit(setOf(work.relayUrl)),
                    behavior = SubscriptionBehavior.Fetch(ENGAGEMENT_FETCH_TIMEOUT_MS),
                    // 打ち切りの判定はリレーが返した件数で行う。キャッシュの再生や、他のリレーで受信済みの
                    // イベントを省く重複排除が入ると、リレーの返した件数と最古の時刻が分からなくなる。
                    deduplicateEvents = false,
                    replayCachedEvents = false,
                ),
            )
        } catch (error: Throwable) {
            eventIds.forEach { eventId ->
                if (engagementHistoryStates[eventId] == EngagementHistoryState.InFlight) {
                    engagementHistoryStates[eventId] = EngagementHistoryState.Partial
                }
            }
            if (error is CancellationException) throw error
            scheduleEngagementHistoryRetry(work)
            return
        }
        if (!started || subscriptionId != subId) {
            eventIds.forEach { eventId ->
                if (engagementHistoryStates[eventId] == EngagementHistoryState.InFlight) {
                    engagementHistoryStates[eventId] = EngagementHistoryState.Partial
                }
            }
            session.close()
            return
        }
        engagementHistorySessions[work.relayUrl] = session
        var closedDisposition: RetryDisposition? = null
        val page = EngagementHistoryPage(work.partitions, eventIds.toSet(), requestedUntil = work.until ?: until)
        jobs += scope.launch {
            session.signals.collect { signal ->
                if (engagementHistorySessions[work.relayUrl] !== session) return@collect
                when (signal) {
                    is SubscriptionSignal.Event -> engagementEventMutex.withLock {
                        if (signal.relayUrl == work.relayUrl) page.record(signal.event)
                        onEvent(signal.event, signal.relayUrl)
                    }
                    is SubscriptionSignal.Closed -> if (signal.relayUrl == work.relayUrl) {
                        closedDisposition = signal.retry
                    }
                    is SubscriptionSignal.FetchCompleted -> {
                        var shouldRetryImmediately = false
                        var shouldRetryWithBackoff = false
                        engagementEventMutex.withLock {
                            val succeeded = !signal.timedOut &&
                                signal.outcomes[work.relayUrl] is RelayOutcome.Eose
                            if (succeeded) {
                                val nextUntil = page.nextUntil(threshold = pageThreshold)
                                eventIds.forEach { eventId ->
                                    if (eventId !in engagementHistoryStates) return@forEach
                                    val cursors = engagementHistoryCursors
                                        .getOrPut(eventId) { mutableMapOf() }
                                        .getOrPut(work.relayUrl) { mutableMapOf() }
                                    work.partitions.forEach { partition ->
                                        val next = nextUntil[partition]
                                        if (next != null) {
                                            cursors[partition] = next
                                        } else {
                                            cursors.remove(partition)
                                            engagementCompletedPartitions
                                                .getOrPut(eventId) { mutableMapOf() }
                                                .getOrPut(work.relayUrl) { mutableSetOf() }
                                                .add(partition)
                                        }
                                    }
                                }
                                engagementFailureCounts.remove(work.relayUrl)
                                engagementRetryJobs.remove(work.relayUrl)?.cancel()
                            } else {
                                val retry = closedDisposition ?: RetryDisposition.RetryWithBackoff
                                if (
                                    retry == RetryDisposition.RetryOnFilterChange &&
                                    work.partitions.size > 1
                                ) {
                                    isolatedEngagementRelays += work.relayUrl
                                    shouldRetryImmediately = true
                                } else if (retry == RetryDisposition.RetryWithBackoff) {
                                    shouldRetryWithBackoff = true
                                } else {
                                    // 認証必須・ブロック・単一フィルターでの拒否は、再送しても同じ結果になる。
                                    // このままだと Partial のまま直ちに再選択されて REQ が繰り返されるため、
                                    // 次の復帰(retryPartialHistory が失敗回数を戻す)まで、このリレーへの取得を止める。
                                    engagementFailureCounts[work.relayUrl] = MAX_ENGAGEMENT_HISTORY_RETRIES + 1
                                }
                            }
                            eventIds.forEach { eventId ->
                                if (eventId !in engagementHistoryStates) return@forEach
                                engagementHistoryStates[eventId] =
                                    if (succeeded || shouldRetryImmediately) {
                                        EngagementHistoryState.Pending
                                    } else {
                                        EngagementHistoryState.Partial
                                    }
                            }
                            log {
                                val result = when {
                                    succeeded -> "EOSE"
                                    shouldRetryImmediately -> "拒否→区分を分けて再取得"
                                    shouldRetryWithBackoff -> "失敗→間隔を空けて再試行"
                                    else -> "失敗→このリレーは次の復帰まで停止"
                                }
                                "history done ${work.relayUrl} $result timedOut=${signal.timedOut} " +
                                    "received[${page.describe()}] next[${
                                        if (succeeded) page.nextUntil(pageThreshold) else "-"
                                    }]"
                            }
                        }
                        engagementHistorySessions.remove(work.relayUrl)
                        session.close()
                        if (started) {
                            if (shouldRetryWithBackoff) {
                                scheduleEngagementHistoryRetry(work)
                            } else {
                                resubscribe()
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun nextEngagementHistoryWork(
        eventIds: List<String>,
        targetRelays: Set<String>,
    ): EngagementHistoryWork? {
        targetRelays.sorted().forEach { relayUrl ->
            val firstEventId = eventIds.firstOrNull { eventId ->
                engagementHistoryStates[eventId] != EngagementHistoryState.Complete &&
                    missingEngagementPartitions(eventId, relayUrl).isNotEmpty()
            } ?: return@forEach
            val missing = missingEngagementPartitions(firstEventId, relayUrl)
            // 続きを取る区分は until が投稿ごとに違いうるので、区分ひとつずつ同じ until の投稿でまとめる。
            val pagedPartition = missing.firstOrNull { historyCursor(firstEventId, relayUrl, it) != null }
            val partitions = when {
                pagedPartition != null -> setOf(pagedPartition)
                relayUrl in isolatedEngagementRelays -> setOf(missing.first())
                else -> missing
            }
            val until = pagedPartition?.let { historyCursor(firstEventId, relayUrl, it) }
            val batch = eventIds.asSequence()
                .filter { engagementHistoryStates[it] != EngagementHistoryState.Complete }
                .filter { eventId -> missingEngagementPartitions(eventId, relayUrl).containsAll(partitions) }
                .filter { eventId -> partitions.all { historyCursor(eventId, relayUrl, it) == until } }
                .take(ENGAGEMENT_HISTORY_BATCH_SIZE)
                .toList()
            if (batch.isNotEmpty()) {
                return EngagementHistoryWork(relayUrl, batch, partitions, until)
            }
        }
        return null
    }

    private fun historyCursor(eventId: String, relayUrl: String, partition: EngagementPartition): Long? =
        engagementHistoryCursors[eventId]?.get(relayUrl)?.get(partition)

    private fun missingEngagementPartitions(eventId: String, relayUrl: String): Set<EngagementPartition> =
        EngagementPartition.entries.toSet() -
            engagementCompletedPartitions[eventId]?.get(relayUrl).orEmpty()

    private fun updateEngagementHistoryState(eventId: String, targetRelays: Set<String>) {
        val complete = targetRelays.all { relayUrl ->
            missingEngagementPartitions(eventId, relayUrl).isEmpty()
        }
        engagementHistoryStates[eventId] = when {
            complete -> EngagementHistoryState.Complete
            engagementHistoryStates[eventId] == EngagementHistoryState.Complete -> EngagementHistoryState.Pending
            else -> engagementHistoryStates[eventId] ?: EngagementHistoryState.Pending
        }
    }

    private fun scheduleEngagementHistoryRetry(work: EngagementHistoryWork) {
        if (!started || engagementRetryJobs[work.relayUrl]?.isActive == true) return
        val attempt = (engagementFailureCounts[work.relayUrl] ?: 0) + 1
        engagementFailureCounts[work.relayUrl] = attempt
        if (attempt > MAX_ENGAGEMENT_HISTORY_RETRIES) return
        val delayMillis = (ENGAGEMENT_RETRY_BASE_DELAY_MS * (1L shl (attempt - 1).coerceAtMost(4)))
            .coerceAtMost(ENGAGEMENT_RETRY_MAX_DELAY_MS)
        engagementRetryJobs[work.relayUrl] = scope.launch {
            delay(delayMillis)
            engagementRetryJobs.remove(work.relayUrl)
            work.eventIds.forEach { eventId ->
                if (engagementHistoryStates[eventId] == EngagementHistoryState.Partial) {
                    engagementHistoryStates[eventId] = EngagementHistoryState.Pending
                }
            }
            resubscribe()
        }
    }

    /** 投稿の付随情報の取得を始める。すでに追跡中なら false。 */
    private fun track(eventId: String): Boolean {
        if (!watchedEventIds.add(eventId)) return false
        engagementHistoryStates[eventId] = EngagementHistoryState.Pending
        while (watchedEventIds.size > MAX_TRACKED_ENGAGEMENT_EVENTS) {
            // 追跡数の上限に達しても、取得進捗(engagementHistoryStates /
            // engagementCompletedPartitions)はここでは消さない。投稿自体がフィードから
            // 消えるまでは forgetEngagementHistory() 経由でのみ破棄する。
            watchedEventIds.remove(watchedEventIds.first())
        }
        requestEngagementResubscribe(immediate = engagementSession == null)
        return true
    }

    /**
     * 付随情報の購読の調停を1つの作業にまとめる。実行中の作業は取り消さない。
     * 取り消すと開きかけの購読が REQ を送った直後に閉じられ、投稿が届くたびに開き直しが続く。
     * 実行中に来た依頼は、終わった後にもう一度だけ調停して反映する。
     */
    private fun requestEngagementResubscribe(immediate: Boolean) {
        engagementResubscribeRequested = true
        if (engagementBatchJob?.isActive == true) return
        engagementBatchJob = scope.launch {
            if (!immediate) delay(ENGAGEMENT_BATCH_DELAY_MS)
            while (engagementResubscribeRequested) {
                engagementResubscribeRequested = false
                resubscribe()
            }
        }
    }

    /**
     * ライブ購読の対象の更新をまとめる。履歴取得の完了ごとに対象が変わるため、
     * そのたびに更新すると同じ購読への REQ が短時間に続く。
     */
    private fun requestLiveEngagementUpdate() {
        liveEngagementUpdateRequested = true
        if (liveEngagementUpdateJob?.isActive == true) return
        liveEngagementUpdateJob = scope.launch {
            while (liveEngagementUpdateRequested) {
                delay(ENGAGEMENT_LIVE_UPDATE_DELAY_MS)
                liveEngagementUpdateRequested = false
                engagementSubscriptionMutex.withLock { updateLiveEngagementLocked() }
            }
        }
    }

    private suspend fun updateLiveEngagementLocked() {
        val session = engagementSession ?: return
        val targetRelays = subscriptions.targetRelayUrls(relayTarget)
        val liveIds = liveEngagementEventIds(watchedEventIds.toList())
        if (liveIds.toSet() == engagementLiveEventIds?.toSet() && targetRelays == engagementLiveRelays) return
        val liveSince = (Clock.System.now().epochSeconds - ENGAGEMENT_LIVE_OVERLAP_SECONDS).coerceAtLeast(0L)
        log { "live update ids=${liveIds.size} since=$liveSince relays=${targetRelays.size}" }
        session.update(engagementFilters(liveIds, since = liveSince), relayTarget)
        engagementLiveEventIds = liveIds
        engagementLiveRelays = targetRelays
    }

    /** 投稿がフィードから完全に消えるとき、リアクション取得の追跡状態も合わせて破棄する。 */
    private fun forget(eventId: String) {
        watchedEventIds.remove(eventId)
        engagementHistoryStates.remove(eventId)
        engagementCompletedPartitions.remove(eventId)
        engagementHistoryCursors.remove(eventId)
    }

    /** 新しい順(タイムライン上の並び時刻)に [MAX_LIVE_ENGAGEMENT_EVENTS] 件まで。時刻不明は新着とみなす。 */
    private fun liveEngagementEventIds(ids: List<String>): List<String> {
        if (ids.size <= MAX_LIVE_ENGAGEMENT_EVENTS) return ids
        return ids
            .sortedByDescending { sortTimeOf(it) ?: Long.MAX_VALUE }
            .take(MAX_LIVE_ENGAGEMENT_EVENTS)
    }

    private fun engagementFilters(
        ids: List<String>,
        since: Long? = null,
        until: Long? = null,
    ): List<NostrFilter> {
        // kind 以外が同一の #e 系(リアクション・kind 1 返信・リポスト)は1本にまとめる。
        // 別々に送るとID一覧を3回送ることになり、REQ が大きくなる。
        val mergedEventTagPartitions = setOf(
            EngagementPartition.Reaction,
            EngagementPartition.KindOneReply,
            EngagementPartition.Repost,
        )
        val merged = NostrFilter(
            kinds = mergedEventTagPartitions.flatMap { it.filter(ids).kinds.orEmpty() },
            eTags = ids,
            since = since,
            until = until,
        )
        return listOf(merged) + EngagementPartition.entries
            .filterNot { it in mergedEventTagPartitions }
            .map { it.filter(ids, since, until) }
    }

    companion object {
        const val MAX_TRACKED_ENGAGEMENT_EVENTS = 100
        const val MAX_LIVE_ENGAGEMENT_EVENTS = 40
        const val ENGAGEMENT_FETCH_TIMEOUT_MS = 10_000L
        const val ENGAGEMENT_BATCH_DELAY_MS = 500L
        const val ENGAGEMENT_LIVE_UPDATE_DELAY_MS = 500L
        const val ENGAGEMENT_HISTORY_BATCH_SIZE = 20
        /** 履歴取得で1フィルターに求める件数。 */
        const val ENGAGEMENT_HISTORY_PAGE_LIMIT = 500
        /**
         * この件数以上届いた区分は、リレー側の上限で打ち切られた可能性があるとみなして続きを取る。
         * リレーの上限は要求した limit より小さいことがあるため、limit より低く置く。
         */
        const val ENGAGEMENT_HISTORY_PAGE_THRESHOLD = 100
        const val MAX_ENGAGEMENT_HISTORY_RETRIES = 4
        const val ENGAGEMENT_RETRY_BASE_DELAY_MS = 1_000L
        const val ENGAGEMENT_RETRY_MAX_DELAY_MS = 30_000L
        const val ENGAGEMENT_LIVE_OVERLAP_SECONDS = 120L
    }
}

private data class EngagementHistoryWork(
    val relayUrl: String,
    val eventIds: List<String>,
    val partitions: Set<EngagementPartition>,
    /** 前のページの続きを取るときの until。null なら最新から取る。 */
    val until: Long? = null,
)

/** 1回の履歴取得で区分ごとに届いた件数と最古の時刻。リレーが上限で打ち切ったかの判定に使う。 */
private class EngagementHistoryPage(
    private val partitions: Set<EngagementPartition>,
    private val eventIds: Set<String>,
    private val requestedUntil: Long,
) {
    private val counts = mutableMapOf<EngagementPartition, Int>()
    private val oldest = mutableMapOf<EngagementPartition, Long>()
    private var ignoresUntil = false

    fun describe(): String =
        partitions.joinToString(" ") { "$it=${counts[it] ?: 0}${oldest[it]?.let { at -> "(最古$at)" } ?: ""}" } +
            if (ignoresUntil) " untilを無視" else ""

    fun record(event: NostrEvent) {
        if (event.createdAt > requestedUntil) ignoresUntil = true
        partitions.filter { it.matches(event, eventIds) }.forEach { partition ->
            counts[partition] = (counts[partition] ?: 0) + 1
            oldest[partition] = minOf(oldest[partition] ?: Long.MAX_VALUE, event.createdAt)
        }
    }

    /**
     * 上限まで届いた区分について、次に取りにいく until を返す。until は境界の秒も含むので、
     * 最古の時刻が前回の until から進まなかったときは同じ要求を繰り返さないよう1秒前へ進める。
     * until より新しいものが届いたリレーは until を守らないので、続きを要求しても同じ結果になる。
     * そのときは続きを取らない。
     */
    fun nextUntil(threshold: Int): Map<EngagementPartition, Long> =
        if (ignoresUntil) emptyMap() else partitions.mapNotNull { partition ->
            if ((counts[partition] ?: 0) < threshold) return@mapNotNull null
            val oldestAt = oldest.getValue(partition)
            partition to if (oldestAt < requestedUntil) oldestAt else requestedUntil - 1
        }.toMap()
}

private enum class EngagementPartition {
    Reaction,
    KindOneReply,
    Nip22Reply,
    Nip22RootReply,
    Repost,
    QuoteRepost;

    fun filter(
        ids: List<String>,
        since: Long? = null,
        until: Long? = null,
        limit: Int? = null,
    ): NostrFilter = when (this) {
        Reaction -> NostrFilter(kinds = listOf(7), eTags = ids, since = since, until = until, limit = limit)
        KindOneReply -> NostrFilter(kinds = listOf(1), eTags = ids, since = since, until = until, limit = limit)
        Nip22Reply -> NostrFilter(
            kinds = listOf(COMMENT_EVENT_KIND),
            rootKindTags = listOf("1"),
            eTags = ids,
            since = since,
            until = until,
            limit = limit,
        )
        Nip22RootReply -> NostrFilter(
            kinds = listOf(COMMENT_EVENT_KIND),
            rootKindTags = listOf("1"),
            rootEventTags = ids,
            since = since,
            until = until,
            limit = limit,
        )
        // kind 1 以外へのリポストは kind 16（NIP-18）。他クライアントの受信も数える。
        Repost -> NostrFilter(kinds = listOf(6, 16), eTags = ids, since = since, until = until, limit = limit)
        QuoteRepost -> NostrFilter(kinds = listOf(1), qTags = ids, since = since, until = until, limit = limit)
    }

    /** [filter]に一致するイベントか。件数の数え上げ用なので、タグの値だけで判定する。 */
    fun matches(event: NostrEvent, ids: Set<String>): Boolean {
        fun hasTag(name: String) = event.tags.any { it.firstOrNull() == name && it.getOrNull(1) in ids }
        return when (this) {
            Reaction -> event.kind == 7 && hasTag("e")
            KindOneReply -> event.kind == 1 && hasTag("e")
            Nip22Reply -> event.kind == COMMENT_EVENT_KIND && hasTag("e")
            Nip22RootReply -> event.kind == COMMENT_EVENT_KIND && hasTag("E")
            Repost -> (event.kind == 6 || event.kind == 16) && hasTag("e")
            QuoteRepost -> event.kind == 1 && hasTag("q")
        }
    }
}

private enum class EngagementHistoryState {
    Pending,
    InFlight,
    Complete,
    Partial,
}

