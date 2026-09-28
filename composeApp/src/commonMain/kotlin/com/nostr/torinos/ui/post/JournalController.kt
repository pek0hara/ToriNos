package com.nostr.torinos.ui.post

import com.nostr.torinos.account.AccountSession
import com.nostr.torinos.engagement.EngagementOperationId
import com.nostr.torinos.engagement.EngagementRequest
import com.nostr.torinos.engagement.NoteEngagementCommand
import com.nostr.torinos.engagement.NoteEngagementState
import com.nostr.torinos.engagement.NoteTarget
import com.nostr.torinos.journal.JournalActivity
import com.nostr.torinos.journal.JournalActivityClassifier
import com.nostr.torinos.journal.JournalActivityKind
import com.nostr.torinos.journal.JournalClock
import com.nostr.torinos.journal.JournalCoverage
import com.nostr.torinos.journal.JournalDateNavigator
import com.nostr.torinos.journal.JournalEngagementAggregator
import com.nostr.torinos.journal.JournalEngagementSource
import com.nostr.torinos.journal.JournalEventSource
import com.nostr.torinos.journal.JournalFetchPlanner
import com.nostr.torinos.journal.JournalFetchRequest
import com.nostr.torinos.journal.JournalFetchResult
import com.nostr.torinos.journal.JournalOwner
import com.nostr.torinos.journal.JournalTimeline
import com.nostr.torinos.journal.NostrJournalEngagementSource
import com.nostr.torinos.journal.NostrJournalEventSource
import com.nostr.torinos.journal.daysOfMonthUntil
import com.nostr.torinos.journal.effectiveJournalKinds
import com.nostr.torinos.journal.isSameMonth
import com.nostr.torinos.journal.journalReferencedEventIds
import com.nostr.torinos.journal.mergeProgressive
import com.nostr.torinos.journal.monthStart
import com.nostr.torinos.journal.nextMonth
import com.nostr.torinos.journal.previousMonth
import com.nostr.torinos.journal.replaceCompleted
import com.nostr.torinos.model.COMMENT_EVENT_KIND
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.ReactionOption
import com.nostr.torinos.network.EventByIdFetcher
import com.nostr.torinos.network.ProfileFetchPolicy
import com.nostr.torinos.network.ProfileRepository
import com.nostr.torinos.network.ReactionEventStore
import com.nostr.torinos.network.TargetEventFetcher
import com.nostr.torinos.ui.SafeCoroutineLauncher
import com.nostr.torinos.ui.timeline.NoteDeletionResult
import com.nostr.torinos.ui.timeline.NoteDeletionService
import com.nostr.torinos.ui.timeline.NoteEngagementCoordinator
import com.nostr.torinos.ui.timeline.StateStore
import com.nostr.torinos.util.journalTraceLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.LocalDate

/** プロフィールの取得と監視。テストではネットワークを使わない実装に差し替える。 */
internal interface JournalProfileSource {
    fun cached(pubkeys: Set<String>): Map<String, NostrProfile>
    fun observe(pubkeys: Set<String>): Flow<Map<String, NostrProfile>>
    suspend fun ensure(pubkeys: Set<String>, relayHint: String?)

    object Repository : JournalProfileSource {
        override fun cached(pubkeys: Set<String>) = ProfileRepository.getCached(pubkeys)
        override fun observe(pubkeys: Set<String>) = ProfileRepository.observe(pubkeys)
        override suspend fun ensure(pubkeys: Set<String>, relayHint: String?) =
            ProfileRepository.ensureProfiles(pubkeys, ProfileFetchPolicy.CacheFirst(PROFILE_MAX_AGE_MS), relayHint)
    }
}

/**
 * ジャーナル画面の取得と状態遷移。
 * 種類の判定・取得計画・取得済み範囲・日付索引・日付移動・エンゲージメント集計は`journal`パッケージの純粋処理に任せ、
 * ここではジョブの取り消し、世代の確認、状態の更新だけを行う。
 */
internal class JournalController(
    targetPubkey: String? = null,
    accountSession: AccountSession? = null,
    scope: CoroutineScope,
    private val clock: JournalClock = JournalClock(),
    private val eventSource: JournalEventSource = NostrJournalEventSource(),
    private val engagementSource: JournalEngagementSource = NostrJournalEngagementSource(),
    private val referenceFetcher: TargetEventFetcher = EventByIdFetcher(),
    private val profileSource: JournalProfileSource = JournalProfileSource.Repository,
    private val cachedReceivedLikes: (pubkey: String, since: Long, until: Long) -> List<NostrEvent> =
        ReactionEventStore::receivedReactions,
) {
    private val isSelf = targetPubkey == null
    private val ownPubkey = accountSession?.signer?.pubkey
    private val owner: JournalOwner? = (targetPubkey ?: ownPubkey)?.let { JournalOwner(it, isSelf) }

    private val launcher = SafeCoroutineLauncher(scope, "JournalController")
    private val store = StateStore(JournalState.initial(isSelf, clock).withDerived())
    val state: StateFlow<JournalState> = store.state

    private val engagementCoordinator = NoteEngagementCoordinator(accountSession?.signer)
    private val noteDeletionService = NoteDeletionService(accountSession?.signer, accountSession?.sessionId)

    private var loadJob: Job? = null
    private var backfillJob: Job? = null
    private var engagementJob: Job? = null
    private var profileObserverJob: Job? = null
    private var relayUrl: String? = null
    private var hasConfiguredRelayUrl = false
    private var monthGeneration = 0L
    private var visibleNoteIds: Set<String> = emptySet()
    private val loadedEngagementNoteIds = mutableSetOf<String>()
    private val watchedPubkeys = mutableSetOf<String>()
    private var nextEngagementOperationId = 0L

    private data class LoadToken(val generation: Long, val month: LocalDate)

    /** ログの先頭に付ける識別子。自分のジャーナルと他人のジャーナルを見分ける。 */
    private val logTag = if (isSelf) "self" else "user:${targetPubkey?.take(8)}"

    private fun log(message: () -> String) = journalTraceLog { "$logTag ${message()}" }

    fun consumeEngagementError() = update { it.copy(engagementError = null) }

    /** 画面で明示的に選んだ種類。空なら既定の種類を表示する。 */
    fun setKinds(selected: Set<JournalActivityKind>) {
        val effective = effectiveJournalKinds(selected, isSelf)
        if (effective == store.value.kinds) return
        log { "setKinds selected=$selected effective=$effective" }
        update { it.copy(kinds = effective) }
        loadMonth(store.value.selectedMonth)
    }

    fun selectDate(date: LocalDate) {
        update { JournalCalendarReducer.reduce(it, JournalCalendarAction.SelectDate(date)) }
        loadDate(date)
    }

    fun previousDate() {
        val current = store.value
        navigateDate(JournalDateNavigator.previous(current.selectedDate, current::datesWithEntries))
    }

    fun nextDate() {
        val current = store.value
        JournalDateNavigator.next(current.selectedDate, clock.today(), current::datesWithEntries)
            ?.let(::navigateDate)
    }

    fun toggleCalendar() = setCalendarVisible(!store.value.showCalendar)

    fun showCalendar() = setCalendarVisible(true)

    fun previousMonth() {
        val current = store.value
        val previous = current.selectedMonth.previousMonth()
        loadMonth(
            previous,
            selectedDate = JournalDateNavigator.lastInMonthOrEnd(previous, clock.today(), current::datesWithEntries),
        )
    }

    fun nextMonth() {
        val current = store.value
        val next = current.selectedMonth.nextMonth()
        val today = clock.today()
        if (next > today.monthStart()) return
        loadMonth(next, selectedDate = JournalDateNavigator.firstInMonthOrStart(next, today, current::datesWithEntries))
    }

    fun refresh() {
        val current = store.value
        if (current.showCalendar) {
            loadDate(current.selectedDate, force = true)
        } else {
            loadMonth(current.selectedMonth, refresh = true)
        }
    }

    fun setRelayUrl(url: String?) {
        if (hasConfiguredRelayUrl && relayUrl == url) {
            log { "setRelayUrl unchanged url=$url isLoading=${store.value.isLoading}" }
            if (store.value.isLoading) loadMonth(store.value.selectedMonth)
            return
        }
        log { "setRelayUrl url=$url (previous=$relayUrl) -> reset" }
        hasConfiguredRelayUrl = true
        relayUrl = url
        loadMonth(store.value.selectedMonth, reset = true)
    }

    fun setVisibleNoteIds(noteIds: Set<String>) {
        val timeline = store.value.timeline
        val available = noteIds.filterTo(mutableSetOf()) { id ->
            timeline[id]?.event?.kind.let { it == 1 || it == COMMENT_EVENT_KIND }
        }
        if (available == visibleNoteIds) return
        visibleNoteIds = available
        val missing = available - loadedEngagementNoteIds
        log { "visibleNotes requested=${noteIds.size} available=${available.size} needEngagement=${missing.size}" }
        if (missing.isNotEmpty()) fetchEngagement(missing)
    }

    fun react(eventId: String, eventPubkey: String) = runEngagementOperation(
        eventId,
        EngagementRequest.AddLike,
        NoteEngagementCommand.AddLike(NoteTarget(eventId, eventPubkey)),
        "リアクションの送信に失敗しました",
    )

    fun unreact(eventId: String) {
        val reactionEventId = store.value.engagementOf(eventId).summary.ownLikeEventId ?: return
        runEngagementOperation(
            eventId,
            EngagementRequest.RemoveLike,
            NoteEngagementCommand.RemoveReaction(reactionEventId),
            "リアクションの解除に失敗しました",
        )
    }

    fun reactWithEmoji(eventId: String, eventPubkey: String, option: ReactionOption) = runEngagementOperation(
        eventId,
        EngagementRequest.AddEmoji(option),
        NoteEngagementCommand.AddEmoji(NoteTarget(eventId, eventPubkey), option),
        "リアクションの送信に失敗しました",
    )

    fun unreactWithEmoji(eventId: String, option: ReactionOption) {
        val reactionEventId = store.value.engagementOf(eventId).summary.ownEmojiReactionEventIds[option.key] ?: return
        runEngagementOperation(
            eventId,
            EngagementRequest.RemoveEmoji(option),
            NoteEngagementCommand.RemoveReaction(reactionEventId),
            "リアクションの解除に失敗しました",
        )
    }

    fun showNoteDeleteDialog(event: NostrEvent) =
        update { it.copy(noteDeleteDialog = JournalNoteDeleteDialogState(event)) }

    fun dismissNoteDeleteDialog() =
        update { if (it.noteDeleteDialog?.isDeleting == true) it else it.copy(noteDeleteDialog = null) }

    fun deleteSelectedNote() {
        val dialog = store.value.noteDeleteDialog ?: return
        if (dialog.isDeleting) return
        update { it.copy(noteDeleteDialog = dialog.copy(isDeleting = true, error = null)) }
        launcher.launch {
            val result = noteDeletionService.delete(dialog.event)
            log { "deleteNote id=${dialog.event.id.take(8)} result=${result::class.simpleName}" }
            update { current ->
                when (result) {
                    NoteDeletionResult.Deleted -> current.copy(
                        timeline = current.timeline.remove(dialog.event.id),
                        noteDeleteDialog = null,
                    )
                    NoteDeletionResult.MissingSigner -> current.withDeleteError("秘密鍵が設定されていません")
                    NoteDeletionResult.NotOwner -> current.withDeleteError("自分の投稿だけ削除できます")
                    is NoteDeletionResult.Failed -> current.withDeleteError(
                        result.cause.message ?: "投稿の削除要求を送信できませんでした",
                    )
                }
            }
        }
    }

    fun close() {
        loadJob?.cancel()
        backfillJob?.cancel()
        engagementJob?.cancel()
        profileObserverJob?.cancel()
    }

    private fun setCalendarVisible(visible: Boolean) {
        if (store.value.showCalendar == visible) return
        update { JournalCalendarReducer.reduce(it, JournalCalendarAction.SetCalendarVisibility(visible)) }
    }

    private fun navigateDate(date: LocalDate) {
        if (date.isSameMonth(store.value.selectedMonth)) {
            selectDate(date)
        } else {
            loadMonth(date.monthStart(), selectedDate = date)
        }
    }

    /**
     * 月を開く。選択日を先に取り、続けて月の残りの日を裏で取る。
     * [refresh]はその月を取り直す。取得が完了した日だけ結果で置き換え、失敗した日は既存を残す。
     * [reset]はリレー切り替えで、取得由来の状態をすべて捨てる。
     */
    private fun loadMonth(
        month: LocalDate,
        selectedDate: LocalDate? = null,
        refresh: Boolean = false,
        reset: Boolean = false,
    ) {
        loadJob?.cancel()
        backfillJob?.cancel()
        engagementJob?.cancel()
        visibleNoteIds = emptySet()
        monthGeneration += 1
        val monthStart = month.monthStart()
        val token = LoadToken(monthGeneration, monthStart)
        val today = clock.today()
        val current = store.value
        val nextSelectedDate = selectedDate?.takeIf { it.isSameMonth(monthStart) }
            ?: current.selectedDate.takeIf { it.isSameMonth(monthStart) }
            ?: monthStart

        log {
            "loadMonth month=$monthStart selected=$nextSelectedDate refresh=$refresh reset=$reset " +
                "generation=$monthGeneration kinds=${current.kinds}"
        }
        val owner = owner
        if (owner == null) {
            log { "loadMonth aborted: no signer" }
            update {
                it.copy(
                    selectedMonth = monthStart,
                    selectedDate = nextSelectedDate,
                    timeline = JournalTimeline.Empty,
                    coverage = JournalCoverage(),
                    isLoading = false,
                    error = "秘密鍵が設定されていません",
                )
            }
            return
        }
        if (reset) loadedEngagementNoteIds.clear()
        if (refresh) loadedEngagementNoteIds -= current.timeline.eventIdsOn(monthStart.daysOfMonthUntil(today))

        update { state ->
            var next = if (reset) {
                state.copy(
                    timeline = JournalTimeline.Empty,
                    coverage = JournalCoverage(),
                    referencedEvents = emptyMap(),
                    engagement = emptyMap(),
                )
            } else {
                state
            }
            if (refresh) next = next.copy(coverage = next.coverage.forgetMonth(monthStart))
            next.withCachedReceivedLikes(owner, monthStart, today).copy(
                selectedMonth = monthStart,
                selectedDate = nextSelectedDate,
                error = null,
            )
        }
        val kinds = store.value.kinds
        if (!hasConfiguredRelayUrl) {
            log { "loadMonth skipped: relay not configured yet" }
            update { it.copy(isLoading = false) }
            return
        }
        if (store.value.coverage.hasLoadedMonth(monthStart, kinds, today)) {
            log { "loadMonth cached: $monthStart already loaded for $kinds ${store.value.summary()}" }
            update { it.copy(isLoading = false) }
            return
        }

        update { it.copy(isLoading = true) }
        val initialJob = launcher.launch {
            try {
                val coverage = store.value.coverage
                val monthReceivedLikes = JournalFetchPlanner.willFetchMonthReceivedLikes(monthStart, today, kinds, coverage)
                val initialKinds = coverage.missing(nextSelectedDate, kinds).let { missing ->
                    if (monthReceivedLikes) missing - JournalActivityKind.ReceivedLike else missing
                }
                val results = fetchAll(
                    JournalFetchPlanner.forDate(nextSelectedDate, initialKinds, owner, relayUrl, clock),
                )
                if (!isCurrent(token)) return@launch logStale("initial", token)
                val accepted = applyResults(results, owner, replace = refresh)
                update { it.copy(isLoading = false) }
                log { "initial done date=$nextSelectedDate ${store.value.summary()}" }
                fetchReferences(accepted)
                watchProfiles(setOf(owner.pubkey))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log { "initial failed: ${e::class.simpleName}: ${e.message}" }
                if (isCurrent(token)) {
                    update { it.copy(isLoading = false, error = e.message ?: "ジャーナルの読み込みに失敗しました") }
                }
            }
        }
        loadJob = initialJob
        backfillJob = launcher.launch {
            // 選択日の取得（または、その間に選び直した日の取得）と取り合わないよう、初回取得の後に始める。
            initialJob.join()
            backfillMonth(token, owner, kinds, today, replace = refresh)
        }
    }

    private suspend fun backfillMonth(
        token: LoadToken,
        owner: JournalOwner,
        kinds: Set<JournalActivityKind>,
        today: LocalDate,
        replace: Boolean,
    ) {
        log { "backfill start month=${token.month}" }
        try {
            JournalFetchPlanner.monthReceivedLikes(token.month, today, kinds, store.value.coverage, owner, clock)
                ?.let { request ->
                    val results = fetchAll(listOf(request))
                    if (!isCurrent(token)) return logStale("backfill receivedLikes", token)
                    fetchReferences(applyResults(results, owner, replace))
                }
            for (date in token.month.daysOfMonthUntil(today)) {
                if (!isCurrent(token)) return logStale("backfill $date", token)
                val missing = store.value.coverage.missing(date, kinds) - JournalActivityKind.ReceivedLike
                if (missing.isEmpty()) continue
                val results = fetchAll(JournalFetchPlanner.forDate(date, missing, owner, relayUrl, clock))
                if (!isCurrent(token)) return logStale("backfill $date", token)
                fetchReferences(applyResults(results, owner, replace))
            }
            log { "backfill done month=${token.month} ${store.value.summary()}" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log { "backfill failed: ${e::class.simpleName}: ${e.message}" }
            if (isCurrent(token)) {
                update { it.copy(isLoading = false, error = e.message ?: "ジャーナルの月間データの読み込みに失敗しました") }
            }
        }
    }

    /** 選択中の月の1日を開く。[force]なら取得済みでも取り直し、完了した分だけ結果で置き換える。 */
    private fun loadDate(date: LocalDate, force: Boolean = false) {
        loadJob?.cancel()
        engagementJob?.cancel()
        visibleNoteIds = emptySet()
        val current = store.value
        if (force) loadedEngagementNoteIds -= current.timeline.eventIdsOn(listOf(date))
        update { it.copy(selectedMonth = date.monthStart(), selectedDate = date, error = null) }
        val owner = owner ?: return
        val token = LoadToken(monthGeneration, date.monthStart())
        val missing = if (force) current.kinds else current.coverage.missing(date, current.kinds)
        log { "loadDate date=$date force=$force missing=$missing" }
        if (missing.isEmpty() || !hasConfiguredRelayUrl) {
            update { it.copy(isLoading = false) }
            loadJob = launcher.launch {
                fetchReferences(store.value.timeline.entries(date, current.kinds).map { it.event })
            }
            return
        }
        update { it.copy(isLoading = true) }
        loadJob = launcher.launch {
            try {
                val results = fetchAll(JournalFetchPlanner.forDate(date, missing, owner, relayUrl, clock))
                if (!isCurrent(token)) return@launch logStale("date $date", token)
                val accepted = applyResults(results, owner, replace = force)
                update { it.copy(isLoading = false) }
                log { "loadDate done date=$date entries=${store.value.visibleEntries.size} ${store.value.summary()}" }
                fetchReferences(accepted)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log { "loadDate failed: ${e::class.simpleName}: ${e.message}" }
                if (isCurrent(token)) {
                    update { it.copy(isLoading = false, error = e.message ?: "この日の投稿の読み込みに失敗しました") }
                }
            }
        }
    }

    private fun isCurrent(token: LoadToken): Boolean =
        monthGeneration == token.generation && store.value.selectedMonth == token.month

    private suspend fun fetchAll(requests: List<JournalFetchRequest>): List<JournalFetchResult> =
        coroutineScope {
            requests.map { request ->
                async {
                    eventSource.fetch(request).also { result ->
                        log {
                            val dates = request.dates.let { if (it.size == 1) "${it.single()}" else "${it.first()}..${it.last()}(${it.size}d)" }
                            "fetch dates=$dates kinds=${request.kinds} target=${request.target} " +
                                "eventKinds=${request.filters.map { it.kinds }} events=${result.events.size} complete=${result.complete}"
                        }
                    }
                }
            }.awaitAll()
        }

    private fun logStale(stage: String, token: LoadToken) = log {
        "dropped stale result stage=$stage token=${token.generation}/${token.month} " +
            "current=$monthGeneration/${store.value.selectedMonth}"
    }

    /**
     * 取得結果を日付索引と取得済み範囲へ反映し、採用したイベントを返す。
     * [replace]なら、全リレーが完了した取得の日付・種類だけを結果で置き換える。
     */
    private fun applyResults(
        results: List<JournalFetchResult>,
        owner: JournalOwner,
        replace: Boolean,
    ): List<NostrEvent> {
        // 分類は状態更新の外で1回だけ行う（更新関数は競合時に再実行されるため）。
        val accepted = results.map { result -> result to result.acceptedActivities(owner) }
        update { state ->
            var timeline = state.timeline
            var coverage = state.coverage
            accepted.forEach { (result, activities) ->
                val request = result.request
                if (replace && result.complete) {
                    request.dates.forEach { date -> timeline = timeline.removeMatching(date, request.kinds) }
                }
                timeline = timeline.upsert(activities)
                if (result.complete) coverage = coverage.markLoaded(request.dates, request.kinds)
            }
            state.copy(timeline = timeline, coverage = coverage)
        }
        log {
            val acceptedCount = accepted.sumOf { (_, activities) -> activities.size }
            val received = accepted.sumOf { (result, _) -> result.events.size }
            "apply accepted=$acceptedCount/$received replace=$replace ${store.value.summary()}"
        }
        return accepted.flatMap { (_, activities) -> activities.map { it.event } }
    }

    private fun JournalFetchResult.acceptedActivities(owner: JournalOwner): List<JournalActivity> =
        events.mapNotNull { event ->
            val kinds = JournalActivityClassifier.classify(event, owner)
            if (request.accepts(kinds)) JournalActivity(event, clock.dateOf(event.createdAt), kinds) else null
        }

    /** `ReactionEventStore`にある、その月のもらったいいねを通信前に表示する。 */
    private fun JournalState.withCachedReceivedLikes(
        owner: JournalOwner,
        monthStart: LocalDate,
        today: LocalDate,
    ): JournalState {
        if (JournalActivityKind.ReceivedLike !in kinds) return this
        val days = monthStart.daysOfMonthUntil(today)
        if (days.isEmpty()) return this
        val activities = cachedReceivedLikes(owner.pubkey, clock.startOfDay(days.first()), clock.endOfDay(days.last()))
            .mapNotNull { event ->
                val kinds = JournalActivityClassifier.classify(event, owner)
                if (JournalActivityKind.ReceivedLike in kinds) {
                    JournalActivity(event, clock.dateOf(event.createdAt), kinds)
                } else {
                    null
                }
            }
        return if (activities.isEmpty()) this else copy(timeline = timeline.upsert(activities))
    }

    private fun fetchEngagement(noteIds: Set<String>) {
        engagementJob?.cancel()
        engagementJob = launcher.launch {
            val aggregator = JournalEngagementAggregator(noteIds, ownPubkey)
            val dirty = mutableSetOf<String>()
            var lastEmission = 0L

            fun emitProgress(force: Boolean) {
                if (dirty.isEmpty()) return
                val now = clock.nowMillis()
                if (!force && now - lastEmission < ENGAGEMENT_STATE_BATCH_MS) return
                val progress = aggregator.snapshot(dirty.toSet())
                update { it.copy(engagement = it.engagement.mergeProgressive(progress)) }
                dirty.clear()
                lastEmission = now
            }

            var received = 0
            val complete = engagementSource.fetch(noteIds, relayUrl) { event ->
                received += 1
                dirty += aggregator.add(event)
                emitProgress(force = false)
            }
            val snapshot = aggregator.snapshot()
            log {
                "engagement notes=${noteIds.size} events=$received withData=${snapshot.size} complete=$complete " +
                    "cachedNotes=${store.value.engagement.size}"
            }
            if (complete) {
                update { it.copy(engagement = it.engagement.replaceCompleted(noteIds, snapshot)) }
                loadedEngagementNoteIds += noteIds
                dirty.clear()
            } else {
                emitProgress(force = true)
            }
            fetchReferences(snapshot.values.flatMap { it.replies })
            watchProfiles(
                snapshot.values.flatMapTo(mutableSetOf()) { engagement ->
                    engagement.reactionEvents.map { it.pubkey } + engagement.repostPubkeys
                },
            )
        }
    }

    /** 行の表示に必要な参照先を取得し、作者と参照先の作者のプロフィールを監視に加える。 */
    private suspend fun fetchReferences(events: Collection<NostrEvent>) {
        if (events.isEmpty()) return
        val known = store.value.referencedEvents
        val ids = events.flatMapTo(mutableSetOf()) { it.journalReferencedEventIds() } - known.keys
        val fetched = mutableListOf<NostrEvent>()
        if (ids.isNotEmpty()) {
            try {
                referenceFetcher.fetch(ids) { event -> fetched += event }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // 取得できなかった参照先は行の側で「読み込み中」と表示する。
            }
            if (fetched.isNotEmpty()) {
                update { it.copy(referencedEvents = it.referencedEvents + fetched.associateBy(NostrEvent::id)) }
            }
            log { "references requested=${ids.size} fetched=${fetched.size} cached=${store.value.referencedEvents.size}" }
        }
        watchProfiles(events.mapTo(mutableSetOf()) { it.pubkey } + fetched.map { it.pubkey })
    }

    private fun watchProfiles(pubkeys: Set<String>) {
        val added = pubkeys - watchedPubkeys
        if (added.isEmpty()) return
        watchedPubkeys += added
        val watched = watchedPubkeys.toSet()
        val cached = profileSource.cached(added)
        log { "profiles added=${added.size} cachedHit=${cached.size} watched=${watched.size}" }
        if (cached.isNotEmpty()) update { it.copy(profiles = it.profiles + cached) }
        profileObserverJob?.cancel()
        profileObserverJob = launcher.launch {
            profileSource.observe(watched).collect { profiles ->
                update { it.copy(profiles = it.profiles + profiles) }
            }
        }
        launcher.launch { profileSource.ensure(added, relayUrl) }
    }

    private fun runEngagementOperation(
        eventId: String,
        request: EngagementRequest,
        command: NoteEngagementCommand,
        failureMessage: String,
    ) {
        val operationId = EngagementOperationId("journal-${++nextEngagementOperationId}")
        val before = store.value.engagementOf(eventId)
        val optimistic = engagementCoordinator.begin(before.summary, operationId, request)
        if (optimistic == before.summary) return
        update { it.withSummary(eventId, optimistic).copy(engagementError = null) }
        launcher.launch {
            var committed = false
            var failure: Throwable? = null
            try {
                val published = engagementCoordinator.execute(command).getOrThrow()
                update {
                    it.withSummary(
                        eventId,
                        engagementCoordinator.commit(it.engagementOf(eventId).summary, operationId, published.id),
                    )
                }
                committed = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                failure = error
            } finally {
                if (!committed) {
                    update {
                        it.withSummary(
                            eventId,
                            engagementCoordinator.rollback(it.engagementOf(eventId).summary, operationId),
                        )
                    }
                }
            }
            if (failure != null) update { it.copy(engagementError = failureMessage) }
        }
    }

    private fun update(transform: (JournalState) -> JournalState) {
        store.dispatch { previous ->
            val today = clock.today()
            val next = transform(previous).let { if (it.today == today) it else it.copy(today = today) }
            if (needsDerivedUpdate(previous, next)) next.withDerived() else next
        }
    }
}

/** ログ用の状態の要約。保持量の増え方を追えるよう件数だけを出す。 */
private fun JournalState.summary(): String =
    "timeline=${timeline.size} coveredDays=${coverage.byDate.size} refs=${referencedEvents.size} " +
        "engagement=${engagement.size} profiles=${profiles.size} month=$selectedMonth selected=$selectedDate"

private fun JournalState.datesWithEntries(month: LocalDate): List<LocalDate> =
    timeline.datesWithEntries(month, kinds)

private fun JournalState.withSummary(
    eventId: String,
    summary: NoteEngagementState,
): JournalState = copy(engagement = engagement + (eventId to engagementOf(eventId).copy(summary = summary)))

private fun JournalState.withDeleteError(message: String): JournalState =
    copy(noteDeleteDialog = noteDeleteDialog?.copy(isDeleting = false, error = message))

private const val ENGAGEMENT_STATE_BATCH_MS = 100L
private const val PROFILE_MAX_AGE_MS = 15 * 60 * 1_000L
