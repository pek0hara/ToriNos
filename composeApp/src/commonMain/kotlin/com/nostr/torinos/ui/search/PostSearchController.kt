package com.nostr.torinos.ui.search

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.ui.SafeCoroutineLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class PageKind { Initial, More }

/** 1ページ分の取得要求。中断後の再開や再試行では同じ要求を新しい購読で送る。 */
internal data class PageRequest(
    val generation: Int,
    val serial: Int,
    val kind: PageKind,
    val until: Long,
    val limit: Int,
    /** 全件が境界秒だったために要求件数を広げて取り直した回数。 */
    val boundaryRetry: Int = 0,
)

/** 正常に完了したページの次の動作。 */
internal sealed interface PagePlan {
    data class End(val end: PageEnd) : PagePlan

    /** 同じ境界で要求件数を広げて取り直す。 */
    data class Widen(val request: PageRequest) : PagePlan
}

/**
 * 正常に完了したページから続きを決める。
 *
 * リレー検索には時刻以外のカーソルがないため、次ページは最古時刻の秒を重ねて要求し、IDで重複を除く。
 * 満杯のページが全件その境界秒だった場合は、同じ境界で要求件数を広げて有限回だけ取り直し、
 * それでも進めなければ打ち切る。完全に取得できたとは表示しない。
 */
internal fun planNextPage(
    request: PageRequest,
    acceptedCount: Int,
    oldestCreatedAt: Long?,
    resultCount: Int,
    config: SearchConfig,
): PagePlan = when {
    oldestCreatedAt == null || acceptedCount < request.limit -> PagePlan.End(PageEnd.Exhausted)
    resultCount >= config.maxResultEvents -> PagePlan.End(PageEnd.ResultLimit)
    oldestCreatedAt < request.until -> PagePlan.End(PageEnd.More(nextUntil = oldestCreatedAt))
    request.boundaryRetry < config.boundaryRetryLimit -> PagePlan.Widen(
        request.copy(limit = request.limit * 2, boundaryRetry = request.boundaryRetry + 1),
    )
    else -> PagePlan.End(PageEnd.BoundaryLimit)
}

/** 正常に完了したページの続き。 */
internal sealed interface PageEnd {
    data class More(val nextUntil: Long) : PageEnd
    data object Exhausted : PageEnd

    /** 検索全体の保持件数の上限に達した。 */
    data object ResultLimit : PageEnd

    /** 同じ時刻の投稿が要求件数の上限を超えて並び、これ以上さかのぼれない。 */
    data object BoundaryLimit : PageEnd
}

internal sealed interface PostPageState {
    data object Idle : PostPageState
    data class Loading(val request: PageRequest) : PostPageState
    data class Interrupted(val request: PageRequest) : PostPageState
    data class Incomplete(val request: PageRequest, val failure: PageFailure) : PostPageState
    data class Complete(val end: PageEnd) : PostPageState
}

internal data class PostSearchUiState(
    /** 検索を実行するたびに変わる。一覧のスクロール位置を新しい検索で先頭へ戻すのに使う。 */
    val searchId: Int = 0,
    val page: PostPageState = PostPageState.Idle,
    val events: List<NostrEvent> = emptyList(),
    val profiles: Map<String, NostrProfile> = emptyMap(),
    val engagements: Map<String, SearchEngagement> = emptyMap(),
)

/** 実行中のページで受け付けたイベントの集計。表示済みかどうかに関係なく、このページの応答として数える。 */
internal class PageProgress(private val limit: Int) {
    private val acceptedIds = mutableSetOf<String>()
    private val overflowIds = mutableSetOf<String>()

    var oldestCreatedAt: Long? = null
        private set
    val acceptedCount: Int get() = acceptedIds.size
    val overflowCount: Int get() = overflowIds.size

    /** ページの要求件数内なら true。超えた分は受け付けずに数えるだけにする。 */
    fun accept(event: NostrEvent): Boolean {
        if (event.id in acceptedIds) return false
        if (acceptedIds.size >= limit) {
            overflowIds += event.id
            return false
        }
        acceptedIds += event.id
        oldestCreatedAt = minOf(oldestCreatedAt ?: event.createdAt, event.createdAt)
        return true
    }
}

/**
 * 投稿検索のページ取得を所有する。
 *
 * ページ状態は [PostPageState] 1つで表し、初回と追加のどちらでも Loading → Complete / Incomplete /
 * Interrupted を同じ規則で遷移する。次ページの境界は正常に完了したページからだけ確定するため、
 * 中断・失敗したページの部分受信で境界が動くことはない。
 *
 * 呼び出しはすべて同じスレッド（メインまたはテストのディスパッチャ）から行う。
 */
internal class PostSearchController(
    scope: CoroutineScope,
    gateway: SearchGateway,
    private val config: SearchConfig,
    private val nowEpochSeconds: () -> Long,
    private val ownerId: String,
    sessionId: String? = null,
    ownPubkey: String? = null,
) {
    private val launcher = SafeCoroutineLauncher(scope, "PostSearchController")
    private val fetcher = PostPageFetcher(gateway, config.searchRelayUrl, config.pageTimeoutMillis)
    private val enricher = SearchResultEnricher(
        scope = scope,
        gateway = gateway,
        config = config,
        nowEpochSeconds = nowEpochSeconds,
        nextSubscriptionId = { "$ownerId-$generation-e${++subscriptionSerial}" },
        onChanged = ::schedulePublish,
        sessionId = sessionId,
        ownPubkey = ownPubkey,
    )

    private val _state = MutableStateFlow(PostSearchUiState())
    val state: StateFlow<PostSearchUiState> = _state.asStateFlow()

    private var query = ""
    private var generation = 0
    private var requestSerial = 0
    private var subscriptionSerial = 0
    private var attempt = 0
    private var page: PostPageState = PostPageState.Idle
    private var visible = true

    private val eventsById = mutableMapOf<String, NostrEvent>()
    private var sortedEvents: List<NostrEvent> = emptyList()
    private var eventsDirty = false

    private var pageJob: Job? = null
    private var publishJob: Job? = null

    val pageState: PostPageState get() = page

    /** 新しい検索語で結果を初期化する。取得は [ensureStarted] で始める。 */
    fun reset(query: String) {
        pageJob?.cancel()
        pageJob = null
        attempt++
        generation++
        requestSerial = 0
        this.query = query
        page = PostPageState.Idle
        eventsById.clear()
        sortedEvents = emptyList()
        eventsDirty = false
        enricher.reset()
        publishNow()
    }

    /** 未取得なら初回ページを、中断していればそのページを取得する。それ以外は何もしない。 */
    fun ensureStarted() {
        if (query.isBlank() || !visible) return
        when (val current = page) {
            PostPageState.Idle -> launchPage(
                PageRequest(
                    generation = generation,
                    serial = ++requestSerial,
                    kind = PageKind.Initial,
                    until = nowEpochSeconds(),
                    limit = config.pageSize,
                ),
            )
            is PostPageState.Interrupted -> launchPage(current.request)
            else -> Unit
        }
    }

    fun loadMore() {
        val end = (page as? PostPageState.Complete)?.end as? PageEnd.More ?: return
        if (!visible) return
        launchPage(
            PageRequest(
                generation = generation,
                serial = ++requestSerial,
                kind = PageKind.More,
                until = end.nextUntil,
                limit = config.pageSize,
            ),
        )
    }

    fun retry() {
        val current = page as? PostPageState.Incomplete ?: return
        if (!visible) return
        launchPage(current.request)
    }

    /** 画面が非表示になった。実行中のページは中断として記録し、付随情報の取得も止める。 */
    fun close() { stop(); enricher.reset() }

    fun stop() {
        if (!visible) return
        visible = false
        attempt++
        val current = page
        if (current is PostPageState.Loading) {
            pageJob?.cancel()
            pageJob = null
            page = PostPageState.Interrupted(current.request)
        }
        enricher.stop()
        publishNow()
    }

    /**
     * 画面へ戻った。付随情報の監視と保留分を再開する。ページの再開は選択中のタブだけで行うため、
     * 呼び出し側が [ensureStarted] を呼ぶ。
     */
    fun resume() {
        if (visible) return
        visible = true
        if (page != PostPageState.Idle) enricher.resume()
        publishNow()
    }

    private fun launchPage(request: PageRequest) {
        pageJob?.cancel()
        val currentAttempt = ++attempt
        page = PostPageState.Loading(request)
        enricher.resume()
        publishNow()
        val progress = PageProgress(request.limit)
        val filter = NostrFilter(kinds = listOf(1), search = query, until = request.until, limit = request.limit)
        val subscriptionId = "$ownerId-${request.generation}-p${++subscriptionSerial}"
        pageJob = launcher.launch {
            val end = fetcher.fetch(subscriptionId, filter) { event ->
                if (attempt != currentAttempt) PageEventDecision.Stop else onPageEvent(progress, event)
            }
            if (attempt != currentAttempt || page != PostPageState.Loading(request)) return@launch
            pageJob = null
            completePage(request, progress, end)
        }
    }

    private fun onPageEvent(progress: PageProgress, event: NostrEvent): PageEventDecision {
        if (event.kind != 1) return PageEventDecision.Continue
        if (!progress.accept(event)) {
            return if (progress.overflowCount > config.pageOverflowBudget) PageEventDecision.Stop
            else PageEventDecision.Continue
        }
        if (event.id !in eventsById && eventsById.size < config.maxResultEvents) {
            eventsById[event.id] = event
            eventsDirty = true
            enricher.onPostAdded(event)
            schedulePublish()
        }
        return PageEventDecision.Continue
    }

    private fun completePage(request: PageRequest, progress: PageProgress, end: PageFetchEnd) {
        if (end is PageFetchEnd.Failed) {
            page = PostPageState.Incomplete(request, end.failure)
            publishNow()
            return
        }
        val plan = planNextPage(
            request = request,
            acceptedCount = progress.acceptedCount,
            oldestCreatedAt = progress.oldestCreatedAt,
            resultCount = eventsById.size,
            config = config,
        )
        when (plan) {
            is PagePlan.Widen -> launchPage(plan.request)
            is PagePlan.End -> {
                page = PostPageState.Complete(plan.end)
                publishNow()
            }
        }
    }

    private fun schedulePublish() {
        if (publishJob?.isActive == true) return
        publishJob = launcher.launch {
            delay(config.stateSyncMillis)
            publishJob = null
            publishNow()
        }
    }

    private fun publishNow() {
        publishJob?.cancel()
        publishJob = null
        if (eventsDirty) {
            sortedEvents = eventsById.values.sortedWith(SEARCH_RESULT_ORDER)
            eventsDirty = false
        }
        _state.value = PostSearchUiState(
            searchId = generation,
            page = page,
            events = sortedEvents,
            profiles = enricher.currentProfiles,
            engagements = enricher.engagementSnapshot(),
        )
    }

    private companion object {
        /** 新しい順。同じ時刻はIDで並べ、受信順で表示が入れ替わらないようにする。 */
        val SEARCH_RESULT_ORDER = compareByDescending<NostrEvent> { it.createdAt }.thenBy { it.id }
    }
}
