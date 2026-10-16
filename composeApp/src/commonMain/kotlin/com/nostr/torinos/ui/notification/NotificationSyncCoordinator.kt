package com.nostr.torinos.ui.notification

import com.nostr.torinos.util.refactorTraceLog
import com.nostr.torinos.network.collectRecovering

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.FiniteFetchResult
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.SubscriptionBehavior
import com.nostr.torinos.network.SubscriptionSession
import com.nostr.torinos.network.SubscriptionSignal
import com.nostr.torinos.network.SubscriptionSpec
import com.nostr.torinos.network.fetchFiniteEvents
import com.nostr.torinos.network.fetchFiniteRange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class NotificationSyncResult(
    val activity: FiniteFetchResult,
    val follows: FiniteFetchResult,
) {
    val canCommitFollowerBaseline: Boolean get() = follows.rangeComplete
}

/** 初回Fetchと画面Liveの所有者を分ける。どちらかの終了で他方を閉じない。 */
internal class NotificationSyncCoordinator(
    private val scope: CoroutineScope,
    private val idPrefix: String,
    private val onActivity: suspend (NostrEvent) -> Unit,
    private val onFollow: suspend (NostrEvent) -> Unit,
    private val open: suspend (SubscriptionSpec) -> SubscriptionSession = NostrRepository::openSubscription,
) {
    private val mutex = Mutex()
    private val sessions = mutableListOf<SubscriptionSession>()
    private val jobs = mutableListOf<Job>()
    private var initialActive = false
    private var visible = false
    private var closed = false
    private var sequence = 0L

    suspend fun sync(
        activityFilters: List<NostrFilter>,
        followsFilter: NostrFilter,
        until: Long,
    ): NotificationSyncResult {
        mutex.withLock {
            check(!closed)
            ensureLive(activityFilters, followsFilter, until)
            initialActive = true
            refactorTraceLog("Notification") { "sync-start initial=$initialActive visible=$visible live=${sessions.size}" }
        }
        try {
            return coroutineScope {
                val activity = async {
                    fetchFiniteEvents(
                        spec("activity-fetch", activityFilters.map { it.copy(until = until) }),
                        open = open, onEvent = { _, event -> onActivity(event) },
                    )
                }
                val follows = async {
                    fetchFiniteRange(
                        spec("follows-fetch", listOf(followsFilter.copy(until = until))),
                        open = open, onEvent = { _, event -> onFollow(event) },
                    )
                }
                NotificationSyncResult(activity.await(), follows.await()).also { result ->
                    refactorTraceLog("Notification") { "sync-result activityAnswered=${result.activity.allAnswered} followsComplete=${result.follows.rangeComplete} commitBaseline=${result.canCommitFollowerBaseline}" }
                }
            }
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    initialActive = false
                    closeIfUnused()
                    refactorTraceLog("Notification") { "sync-end initial=$initialActive visible=$visible live=${sessions.size}" }
                }
            }
        }
    }

    suspend fun setVisible(
        value: Boolean,
        activityFilters: List<NostrFilter>,
        followsFilter: NostrFilter,
        now: Long,
    ) = mutex.withLock {
        if (closed) return@withLock
        visible = value
        refactorTraceLog("Notification") { "visibility=$value initial=$initialActive live=${sessions.size}" }
        if (value) ensureLive(activityFilters, followsFilter, now) else closeIfUnused()
    }

    private suspend fun ensureLive(activity: List<NostrFilter>, follows: NostrFilter, now: Long) {
        if (sessions.isNotEmpty()) return
        try {
            val liveSince = (now - FOLLOW_SYNC_OVERLAP_SECONDS).coerceAtLeast(0)
            listOf(activity to onActivity, listOf(follows) to onFollow).forEachIndexed { index, (filters, callback) ->
                val session = open(spec("live-$index", filters.map {
                    // フォロー差分が古い場合も、その範囲を切らない。
                    it.copy(since = minOf(it.since ?: liveSince, liveSince), until = null)
                }).copy(behavior = SubscriptionBehavior.Live))
                sessions += session
                refactorTraceLog("Notification") { "live-open index=$index owned=${sessions.size}" }
                jobs += scope.launch {
                    session.collectRecovering { if (it is SubscriptionSignal.Event) callback(it.event) }
                }
            }
        } catch (error: Throwable) {
            refactorTraceLog("Notification") { "live-start-failed type=${error::class.simpleName}" }
            withContext(NonCancellable) { closeLive() }
            throw error
        }
    }

    private fun spec(name: String, filters: List<NostrFilter>) = SubscriptionSpec(
        id = "$idPrefix-$name-${sequence++}", filters = filters,
        behavior = SubscriptionBehavior.Fetch(10_000),
    )

    private suspend fun closeIfUnused() {
        if (!initialActive && !visible) closeLive()
    }

    private suspend fun closeLive() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        val owned = sessions.toList()
        sessions.clear()
        owned.forEach { it.close() }
        refactorTraceLog("Notification") { "live-close count=${owned.size} remaining=${sessions.size}" }
    }

    suspend fun close() = withContext(NonCancellable) {
        mutex.withLock {
            closed = true
            closeLive()
        }
    }
}
