package com.nostr.torinos.ui.channel

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.ChannelReadingPosition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 未取得区間の両端。画面では olderEdgeId の直前に区切りを表示する。 */
data class ChannelHistoryGap(val newerEdgeId: String, val olderEdgeId: String)
enum class ChannelNavigationTarget { Latest, Previous }
data class ChannelScrollRequest(
    val sequence: Long,
    val messageId: String,
    val offset: Int = 0,
    val animated: Boolean = false,
)
data class ChannelHistoryState(
    val messages: List<NostrEvent> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val notice: String? = null,
    val canLoadOlder: Boolean = false,
    val hasPreviousPosition: Boolean = false,
    val gap: ChannelHistoryGap? = null,
    val navigation: ChannelScrollRequest? = null,
    val navigationTarget: ChannelNavigationTarget? = null,
    val newMessageCount: Int = 0,
)

internal data class ChannelHistoryPage(val events: List<NostrEvent>, val complete: Boolean)

/** 本文の公開はページの終了時だけ行う。付随情報の更新はこの状態を変更しない。 */
internal class ChannelHistory(
    private val scope: CoroutineScope,
    private val channelId: String,
    private val fetch: suspend (NostrFilter) -> ChannelHistoryPage,
    private val lookup: suspend (String) -> NostrEvent?,
) {
    private val mutableState = MutableStateFlow(ChannelHistoryState())
    val state = mutableState.asStateFlow()
    private var previous: ChannelReadingPosition? = null
    private var olderCursor: Long? = null
    private var sequence = 0L
    private var job: Job? = null
    private var stopped = false
    private var atLatest = true
    private var latestConfirmed = false
    private var viewportAnchorId: String? = null
    private val pendingLive = linkedMapOf<String, NostrEvent>()
    private var retryAction: (() -> Unit)? = null

    fun initialize(cached: List<NostrEvent>, position: ChannelReadingPosition?) {
        previous = position // この回の保存で「前回の位置」を上書きしない。
        mutableState.value = ChannelHistoryState(messages = sorted(cached), hasPreviousPosition = position != null)
        refreshLatest()
    }

    fun setAtLatest(value: Boolean) {
        atLatest = value
        if (value && state.value.newMessageCount != 0) mutableState.value = state.value.copy(newMessageCount = 0)
    }

    fun setViewport(anchorId: String?) {
        if (anchorId != null) viewportAnchorId = anchorId
    }

    /** 読み込み済みの最新位置への移動。通常は通信を発生させない。 */
    fun latest() {
        if (!latestConfirmed) {
            mutableState.value = state.value.copy(navigationTarget = ChannelNavigationTarget.Latest)
            refreshLatest(userInitiated = true)
            return
        }
        job?.takeIf { it.isActive }?.cancel()
        atLatest = true
        mutableState.value = state.value.copy(
            isLoading = false,
            navigationTarget = ChannelNavigationTarget.Latest,
            newMessageCount = 0,
        )
        state.value.messages.firstOrNull()?.let { navigate(it.id, animated = true) }
            ?: run { mutableState.value = state.value.copy(navigationTarget = null) }
    }

    private fun refreshLatest(userInitiated: Boolean = false): Unit = request({ refreshLatest(userInitiated) }) {
        val page = fetch(filter())
        val before = state.value
        val incoming = sorted(page.events + pendingLive.values)
        val messages = sorted(before.messages + incoming)
        // 最新確認は既存の履歴を捨てずに統合する。範囲が接続していなければ区切りを残す。
        latestConfirmed = page.complete
        pendingLive.clear()
        olderCursor = messages.lastOrNull()?.createdAt
        mutableState.value = before.copy(
            messages = messages,
            gap = latestGap(before.messages, incoming, before.gap),
            canLoadOlder = before.canLoadOlder || (page.complete && page.events.size >= PAGE_SIZE),
            error = if (page.complete) null else INCOMPLETE,
            newMessageCount = 0,
        )
        atLatest = true
        messages.firstOrNull()?.let { navigate(it.id, animated = userInitiated) }
            ?: run { mutableState.value = state.value.copy(navigationTarget = null) }
    }

    fun older() {
        if (!latestConfirmed || !state.value.canLoadOlder) return
        val until = olderCursor ?: return
        request({ older() }) {
            val limit = pageLimit(until)
            val page = fetch(filter(until = until, limit = limit))
            merge(page.events)
            if (page.complete) olderCursor = page.events.minOfOrNull { it.createdAt } ?: until
            mutableState.value = state.value.copy(
                canLoadOlder = !page.complete || page.events.size >= limit,
                error = if (page.complete) null else INCOMPLETE,
            )
        }
    }

    fun previous() {
        val position = previous ?: return
        if (state.value.isLoading) return
        mutableState.value = state.value.copy(navigationTarget = ChannelNavigationTarget.Previous)
        state.value.messages.firstOrNull { it.id == position.messageId && state.value.error == null }?.let {
            atLatest = false
            navigate(it.id, position.scrollOffset)
            return
        }
        request({ previous() }) {
            val savedEvent = if (position.createdAt == null) lookup(position.messageId) else null
            val timestamp = position.createdAt ?: savedEvent?.createdAt
            if (timestamp == null) {
                mutableState.value = state.value.copy(
                    error = "前回の位置を取得できませんでした。再試行できます。",
                    navigationTarget = null,
                )
                return@request
            }
            val page = fetch(filter(until = timestamp))
            val exact = page.events.firstOrNull { it.id == position.messageId }
                ?: savedEvent ?: lookup(position.messageId)
            val oldWindow = sorted(page.events + listOfNotNull(exact))
            val target = oldWindow.firstOrNull { it.id == position.messageId }
                ?: oldWindow.minByOrNull { kotlin.math.abs(it.createdAt - timestamp) }
            if (target == null) {
                mutableState.value = state.value.copy(
                    error = "前回の位置付近の投稿を取得できませんでした。再試行できます。",
                    navigationTarget = null,
                )
                return@request
            }
            val newerEdge = state.value.messages.lastOrNull()
            val olderEdge = oldWindow.first()
            val gap = if (newerEdge != null && newerEdge.createdAt > olderEdge.createdAt)
                ChannelHistoryGap(newerEdge.id, olderEdge.id) else state.value.gap
            merge(oldWindow)
            olderCursor = if (page.complete) oldWindow.last().createdAt else timestamp
            latestConfirmed = true
            mutableState.value = state.value.copy(
                gap = gap,
                canLoadOlder = !page.complete || oldWindow.size >= PAGE_SIZE,
                error = if (page.complete) null else INCOMPLETE,
                notice = if (target.id != position.messageId) "前回の投稿が見つからないため、近い時刻の投稿へ移動しました。" else null,
            )
            atLatest = false
            viewportAnchorId = target.id
            navigate(target.id, if (target.id == position.messageId) position.scrollOffset else 0)
        }
    }

    fun fillGap() {
        val gap = state.value.gap ?: return
        val newer = state.value.messages.firstOrNull { it.id == gap.newerEdgeId } ?: return
        val older = state.value.messages.firstOrNull { it.id == gap.olderEdgeId } ?: return
        request({ fillGap() }) {
            val limit = pageLimit(newer.createdAt)
            val page = fetch(filter(until = newer.createdAt, since = older.createdAt, limit = limit))
            merge(page.events)
            if (page.complete) {
                val edge = page.events.minByOrNull { it.createdAt }
                mutableState.value = state.value.copy(gap = when {
                    edge == null || edge.createdAt <= older.createdAt || page.events.size < limit -> null
                    else -> ChannelHistoryGap(edge.id, older.id)
                })
            }
            mutableState.value = state.value.copy(error = if (page.complete) null else INCOMPLETE)
        }
    }

    fun retry() { if (!state.value.isLoading) retryAction?.invoke() }

    fun receive(event: NostrEvent) {
        if (stopped) return
        if (state.value.messages.any { it.id == event.id } || event.id in pendingLive) return
        if (state.value.isLoading) {
            pendingLive[event.id] = event
            return
        }
        merge(listOf(event))
        trimToLimit()
        if (atLatest) state.value.messages.firstOrNull()?.let { navigate(it.id) }
        else mutableState.value = state.value.copy(newMessageCount = state.value.newMessageCount + 1)
    }

    fun consumeNavigation(sequence: Long) {
        if (state.value.navigation?.sequence == sequence) {
            mutableState.value = state.value.copy(navigation = null, navigationTarget = null)
        }
    }

    fun consumeNotice() { mutableState.value = state.value.copy(notice = null) }

    private fun navigate(id: String, offset: Int = 0, animated: Boolean = false) {
        mutableState.value = state.value.copy(
            navigation = ChannelScrollRequest(++sequence, id, offset, animated),
        )
    }

    private fun request(retry: () -> Unit, block: suspend () -> Unit) {
        if (stopped || job?.isActive == true) return
        retryAction = retry
        mutableState.value = state.value.copy(isLoading = true, error = null)
        job = scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = state.value.copy(error = INCOMPLETE, navigationTarget = null)
            } finally {
                if (!stopped) {
                    if (pendingLive.isNotEmpty()) {
                        val count = pendingLive.size
                        merge(pendingLive.values.toList())
                        pendingLive.clear()
                        if (!atLatest) mutableState.value = state.value.copy(newMessageCount = state.value.newMessageCount + count)
                    }
                    trimToLimit()
                    mutableState.value = state.value.copy(isLoading = false)
                }
            }
        }
    }

    private fun merge(events: List<NostrEvent>) {
        mutableState.value = state.value.copy(messages = sorted(state.value.messages + events))
    }

    private fun latestGap(
        existing: List<NostrEvent>,
        incoming: List<NostrEvent>,
        knownGap: ChannelHistoryGap?,
    ): ChannelHistoryGap? {
        if (knownGap != null || existing.isEmpty() || incoming.isEmpty()) return knownGap
        val existingIds = existing.mapTo(mutableSetOf()) { it.id }
        if (incoming.any { it.id in existingIds }) return null

        val incomingOldest = incoming.last()
        val olderExisting = existing.firstOrNull {
            it.createdAt < incomingOldest.createdAt ||
                (it.createdAt == incomingOldest.createdAt && it.id < incomingOldest.id)
        } ?: return null
        return ChannelHistoryGap(incomingOldest.id, olderExisting.id)
    }

    private fun trimToLimit() {
        val messages = state.value.messages
        if (messages.size <= MAX_VISIBLE_MESSAGES) return

        val latest = messages.take(LATEST_WINDOW_SIZE)
        val anchorIndex = messages.indexOfFirst { it.id == viewportAnchorId }
            .takeIf { it >= LATEST_WINDOW_SIZE }
            ?: LATEST_WINDOW_SIZE
        val windowSize = MAX_VISIBLE_MESSAGES - latest.size
        val preferredStart = anchorIndex - FOCUS_NEWER_MESSAGES
        val maxStart = messages.size - windowSize
        val start = preferredStart.coerceIn(LATEST_WINDOW_SIZE, maxStart)
        val focusWindow = messages.subList(start, start + windowSize)
        val retained = latest + focusWindow
        val retainedIds = retained.mapTo(mutableSetOf()) { it.id }
        val existingGap = state.value.gap?.takeIf {
            it.newerEdgeId in retainedIds && it.olderEdgeId in retainedIds
        }
        val gap = if (start > LATEST_WINDOW_SIZE) {
            ChannelHistoryGap(latest.last().id, focusWindow.first().id)
        } else {
            existingGap
        }
        olderCursor = retained.last().createdAt
        mutableState.value = state.value.copy(
            messages = retained,
            gap = gap,
            canLoadOlder = true,
        )
    }

    // 時刻境界を含めて取り直し、同じ秒の既取得件数分を増やして取りこぼしを避ける。
    private fun pageLimit(until: Long): Int = PAGE_SIZE + state.value.messages.count { it.createdAt == until }
    private fun filter(until: Long? = null, since: Long? = null, limit: Int = PAGE_SIZE) =
        NostrFilter(kinds = listOf(42), eTags = listOf(channelId), until = until, since = since, limit = limit)

    fun close() { stopped = true; job?.cancel() }

    companion object {
        const val PAGE_SIZE = 30
        const val MAX_VISIBLE_MESSAGES = 500
        private const val LATEST_WINDOW_SIZE = PAGE_SIZE
        private const val FOCUS_NEWER_MESSAGES = 100
        private const val INCOMPLETE = "投稿の取得が完了していません。表示済みの投稿を残しています。再試行してください。"
        private fun sorted(events: List<NostrEvent>) = events.distinctBy { it.id }
            .sortedWith(compareByDescending<NostrEvent> { it.createdAt }.thenByDescending { it.id })
    }
}

/** ミュートされた投稿が区切りの端でも、未取得区間の表示を失わない。 */
internal fun ChannelHistoryState.gapIndex(visibleMessages: List<NostrEvent>): Int? {
    val edge = gap?.olderEdgeId ?: return null
    val rawIndex = messages.indexOfFirst { it.id == edge }
    if (rawIndex < 0) return null
    val olderIds = messages.drop(rawIndex).mapTo(mutableSetOf()) { it.id }
    return visibleMessages.indexOfFirst { it.id in olderIds }.takeIf { it >= 0 } ?: visibleMessages.size
}
