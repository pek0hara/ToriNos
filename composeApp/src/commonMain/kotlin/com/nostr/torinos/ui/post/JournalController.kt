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
import com.nostr.torinos.journal.JournalInteractionSource
import com.nostr.torinos.journal.JournalMemberStorage
import com.nostr.torinos.journal.JournalMembers
import com.nostr.torinos.journal.JournalNoteEngagement
import com.nostr.torinos.journal.JournalRangePager
import com.nostr.torinos.journal.JournalReferenceSource
import com.nostr.torinos.journal.JournalRelayRetries
import com.nostr.torinos.journal.JournalTimeline
import com.nostr.torinos.journal.NostrJournalEngagementSource
import com.nostr.torinos.journal.NostrJournalEventSource
import com.nostr.torinos.journal.NostrJournalInteractionSource
import com.nostr.torinos.journal.NostrJournalReferenceSource
import com.nostr.torinos.journal.daysOfMonthUntil
import com.nostr.torinos.journal.effectiveJournalKinds
import com.nostr.torinos.journal.isSameMonth
import com.nostr.torinos.journal.journalReferencedEventIds
import com.nostr.torinos.journal.mergeProgressive
import com.nostr.torinos.journal.monthStart
import com.nostr.torinos.journal.nextMonth
import com.nostr.torinos.journal.previousMonth
import com.nostr.torinos.journal.rankJournalInteractions
import com.nostr.torinos.journal.replaceCompleted
import com.nostr.torinos.model.COMMENT_EVENT_KIND
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.ReactionOption
import com.nostr.torinos.network.ProfileFetchPolicy
import com.nostr.torinos.network.ProfileRepository
import com.nostr.torinos.network.RelayTarget
import com.nostr.torinos.ui.SafeCoroutineLauncher
import com.nostr.torinos.ui.timeline.NoteDeletionResult
import com.nostr.torinos.ui.timeline.NoteDeletionService
import com.nostr.torinos.ui.timeline.NoteEngagementCoordinator
import com.nostr.torinos.ui.timeline.StateStore
import com.nostr.torinos.util.journalTraceLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
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
    private val referenceSource: JournalReferenceSource = NostrJournalReferenceSource(),
    private val profileSource: JournalProfileSource = JournalProfileSource.Repository,
    private val memberStorage: JournalMemberStorage = JournalMemberStorage.Local,
    private val interactionSource: JournalInteractionSource = NostrJournalInteractionSource(),
    initialShowCalendar: Boolean = false,
) {
    private val isSelf = targetPubkey == null
    private val ownPubkey = accountSession?.signer?.pubkey
    /** 自分のジャーナルなら自分、他人のジャーナルならその人。メンバーの先頭で、外せない。 */
    private val primaryPubkey: String? = targetPubkey ?: ownPubkey

    private val launcher = SafeCoroutineLauncher(scope, "JournalController")
    private val store = StateStore(JournalState.initial(isSelf, listOfNotNull(primaryPubkey), clock, initialShowCalendar).withDerived())
    val state: StateFlow<JournalState> = store.state

    private val engagementCoordinator = NoteEngagementCoordinator(accountSession?.signer)
    private val noteDeletionService = NoteDeletionService(accountSession?.signer, accountSession?.sessionId)

    private var loadJob: Job? = null
    private var backfillJob: Job? = null
    private var engagementJob: Job? = null
    /** 取り終えなかったリレーだけを取り直す。月の移動・リレーの切り替え・手動の更新でやめる。 */
    private val relayRetries = JournalRelayRetries(
        launch = { block -> launcher.launch { block() } },
        log = { message -> log(message) },
    )
    private var profileObserverJob: Job? = null
    private var relayUrl: String? = null
    private var hasConfiguredRelayUrl = false
    /** 保存したメンバーを読み終えたか。読み終えるまでは、後で取り直さないよう取得を始めない。 */
    private var hasLoadedMembers = !isSelf || ownPubkey == null
    private var candidatesJob: Job? = null
    private var saveJob: Job? = null
    private var monthGeneration = 0L
    private var lastCalendarToggleRequest = 0
    private var visibleNoteIds: Set<String> = emptySet()
    private val loadedEngagementNoteIds = mutableSetOf<String>()
    private val watchedPubkeys = mutableSetOf<String>()
    private var nextEngagementOperationId = 0L

    private data class LoadToken(val generation: Long, val month: LocalDate)

    /** ログの先頭に付ける識別子。自分のジャーナルと他人のジャーナルを見分ける。 */
    private val logTag = if (isSelf) "self" else "user:${targetPubkey?.take(8)}"

    private fun log(message: () -> String) = journalTraceLog { "$logTag ${message()}" }

    init {
        val own = ownPubkey
        if (!hasLoadedMembers && own != null) {
            launcher.launch {
                // 読めなくても自分だけで表示できるよう、失敗は空として扱う。
                val saved = try {
                    memberStorage.load(own)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    emptyList()
                }
                    .filter { it != own }
                    .distinct()
                    .take(JOURNAL_MAX_ADDED_MEMBERS)
                hasLoadedMembers = true
                log { "members loaded saved=${saved.size}" }
                // 開いた直後は何も選ばず自分だけを表示し、保存したメンバーはチップだけ出しておく。
                // 選んでいないメンバーは取得しないので、待たせていた取得は自分の分だけになる。
                applyMembers(listOf(own) + saved, selected = emptySet())
                loadMonth(store.value.selectedMonth)
            }
        }
    }

    fun consumeEngagementError() = update { it.copy(engagementError = null) }

    /** 画面で明示的に選んだ種類。空なら既定の種類を表示する。 */
    fun setKinds(selected: Set<JournalActivityKind>) {
        val effective = effectiveJournalKinds(selected)
        if (effective == store.value.kinds) return
        log { "setKinds selected=$selected effective=$effective" }
        update { it.copy(kinds = effective) }
        loadMonth(store.value.selectedMonth)
    }

    /**
     * メンバーを追加して選び、追加した人の分を取る。
     * 誰も選んでいなければ追加した人だけを、選んでいればその選択に足して表示する。
     * 保存したメンバーを読み終える前は、読み込みで上書きされるため受け付けない。
     */
    fun addMember(pubkey: String) {
        val current = store.value
        if (!hasLoadedMembers || !current.canAddMember || pubkey in current.members) return
        saveAndApplyMembers(current.members + pubkey, selected = current.selectedMembers + pubkey)
    }

    /** メンバーを外す。先頭（自分、または開いた人）は外せない。 */
    fun removeMember(pubkey: String) {
        val current = store.value
        if (!hasLoadedMembers || pubkey == primaryPubkey || pubkey !in current.members) return
        saveAndApplyMembers(current.members - pubkey)
    }

    /**
     * メンバーの選択を切り替える。誰も選んでいなければ先頭のメンバーだけを表示する。
     * 取得するのは表示中のメンバーだけなので、表示することになった人の分がこの月で足りなければ取る。
     * 外すときは取得済みの分を残し、また選べばすぐ出す。
     */
    fun toggleMemberSelection(pubkey: String) {
        update { state ->
            if (pubkey !in state.members) return@update state
            val selected = state.selectedMembers
            state.copy(selectedMembers = if (pubkey in selected) selected - pubkey else selected + pubkey)
        }
        val current = store.value
        if (!current.hasLoadedMonthFor(current.fetchMembers, clock.today())) {
            loadMonth(current.selectedMonth)
        }
    }

    /**
     * メンバー候補を、先頭のメンバー（自分、または開いた人）と直近のやりとりが多い順に集める。
     * 取得は全リレーの応答（か時間切れ）を待つため、届いた分で並べて先に出し、終わったら全件で並べ直す。
     */
    fun loadMemberCandidates() {
        val subject = primaryPubkey ?: return
        if (candidatesJob?.isActive == true) return
        val fetchedAt = store.value.memberCandidates.fetchedAtMillis
        if (fetchedAt != null && clock.nowMillis() - fetchedAt < CANDIDATE_CACHE_MS) return
        update { it.copy(memberCandidates = it.memberCandidates.copy(isLoading = true)) }
        candidatesJob = launcher.launch {
            val received = MutableStateFlow<List<NostrEvent>>(emptyList())
            @OptIn(FlowPreview::class)
            val partialJob = launch {
                // 何も届いていないうちは出さない（前に取った候補を空にしない）。
                received.filter { it.isNotEmpty() }.sample(CANDIDATE_PARTIAL_INTERVAL_MS).collect { events ->
                    val partial = rankCandidates(events, subject)
                    update { it.copy(memberCandidates = it.memberCandidates.copy(candidates = partial)) }
                    watchProfiles(partial.mapTo(mutableSetOf()) { it.pubkey })
                }
            }
            try {
                val since = clock.nowMillis() / 1_000 - CANDIDATE_LOOKBACK_SECONDS
                val events = interactionSource.fetch(subject, since) { event -> received.update { it + event } }
                // 途中の並べ直しが、全件の結果を後から上書きしないよう止め終わるのを待つ。
                partialJob.cancelAndJoin()
                val ranked = rankCandidates(events, subject)
                log { "candidates ranked=${ranked.size}" }
                update {
                    it.copy(memberCandidates = JournalMemberCandidates(candidates = ranked, fetchedAtMillis = clock.nowMillis()))
                }
                watchProfiles(ranked.mapTo(mutableSetOf()) { it.pubkey })
            } finally {
                partialJob.cancel()
                update { it.copy(memberCandidates = it.memberCandidates.copy(isLoading = false)) }
            }
        }
    }

    private fun rankCandidates(events: List<NostrEvent>, subject: String): List<JournalMemberCandidate> =
        rankJournalInteractions(events, subject)
            .take(CANDIDATE_LIMIT)
            .map { (pubkey, count) -> JournalMemberCandidate(pubkey, count) }

    /** 今日より先の日は記録がないため選ばせない。 */
    fun selectDate(date: LocalDate) {
        if (date > clock.today()) return
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

    fun goToToday() = navigateDate(clock.today())

    fun toggleCalendar() = setCalendarVisible(!store.value.showCalendar)

    // 画面へ戻ると LaunchedEffect が再起動するため、処理済みの要求は再実行しない。
    fun applyCalendarToggleRequest(request: Int) {
        if (request <= lastCalendarToggleRequest) return
        lastCalendarToggleRequest = request
        toggleCalendar()
    }

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
        candidatesJob?.cancel()
    }

    /** 自分のジャーナルだけ保存する。他人のジャーナルで足したメンバーは、その画面を開いている間だけ。 */
    private fun saveAndApplyMembers(members: List<String>, selected: Set<String>? = null) {
        applyMembers(members, selected)
        val own = ownPubkey ?: return
        if (!isSelf) return
        // 続けて操作しても古い一覧で上書きしないよう、前の保存を待ってから書く。
        val previous = saveJob
        saveJob = launcher.launch {
            previous?.join()
            memberStorage.save(own, members.filter { it != own })
        }
    }

    /**
     * メンバーを入れ替える。取得済みの行は捨てずに新しいメンバーで判定し直し、外した人の取得記録だけ消す。
     * 追加した人が表示中なら、その人の分だけを取る（ほかの人は取得済みなので取り直さない）。
     * [selected]を渡せば選択を置き換え、渡さなければ残ったメンバーの選択を保つ。
     */
    private fun applyMembers(members: List<String>, selected: Set<String>? = null) {
        val previous = store.value.members
        if (members == previous) return
        val added = members - previous.toSet()
        val removed = previous - members.toSet()
        log { "applyMembers count=${members.size} added=${added.size} removed=${removed.size}" }
        val classification = JournalMembers(members.toSet())
        update {
            it.copy(
                members = members,
                selectedMembers = (selected ?: it.selectedMembers) intersect members.toSet(),
                timeline = it.timeline.reclassify { event -> JournalActivityClassifier.classify(event, classification) },
                coverage = it.coverage.forgetMembers(removed),
            )
        }
        watchProfiles(members.toSet())
        // 選んでいた人を外して先頭のメンバーだけの表示に戻ったときは、その分が足りなければ取る。
        val current = store.value
        val needsFetch = added.any { it in current.fetchMembers } ||
            (removed.isNotEmpty() && !current.hasLoadedMonthFor(current.fetchMembers, clock.today()))
        if (needsFetch) loadMonth(current.selectedMonth)
    }

    /** 種類の判定に使う全メンバー。取得は[JournalState.fetchMembers]だけを対象にする。 */
    private fun currentMembers(): JournalMembers? =
        store.value.members.takeIf { it.isNotEmpty() }?.let { JournalMembers(it.toSet()) }

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
     * [reset]はリレー切り替えで、選んだリレーから取った状態を捨てる。ID で取った参照先は中身が変わらないため残す。
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
        // 種類やメンバーの切り替えでは、待っている取り直しを続ける（取得済みの記録はもう付いている）。
        if (reset || refresh || monthStart != current.selectedMonth) relayRetries.cancelAll()
        val nextSelectedDate = selectedDate?.takeIf { it.isSameMonth(monthStart) }
            ?: current.selectedDate.takeIf { it.isSameMonth(monthStart) }
            ?: monthStart

        log {
            "loadMonth month=$monthStart selected=$nextSelectedDate refresh=$refresh reset=$reset " +
                "generation=$monthGeneration kinds=${current.kinds}"
        }
        val members = currentMembers()
        if (members == null) {
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
                    engagement = emptyMap(),
                )
            } else {
                state
            }
            if (refresh) next = next.copy(coverage = next.coverage.forgetMonth(monthStart))
            next.copy(
                selectedMonth = monthStart,
                selectedDate = nextSelectedDate,
                error = null,
                // 前の月の取得を途中でやめた場合も、ここで戻す（取得するなら下でまた立てる）。
                isBackfilling = false,
            )
        }
        val kinds = store.value.kinds
        // 取得するのは表示中のメンバーだけ。種類の判定（[members]）は非表示のメンバーも含めて行う。
        val targets = store.value.fetchMembers
        if (!hasConfiguredRelayUrl || !hasLoadedMembers) {
            log { "loadMonth skipped: relay=$hasConfiguredRelayUrl members=$hasLoadedMembers" }
            // リレーとメンバーがそろったら読み込むので、それまでは読み込み中のままにする（「ありません」を出さない）。
            return
        }
        if (store.value.coverage.hasLoadedMonth(monthStart, kinds, today, targets)) {
            log { "loadMonth cached: $monthStart already loaded for $kinds ${store.value.summary()}" }
            update { it.copy(isLoading = false) }
            // 月の取得の最後にまとめて取る参照先は、取得中に月を離れると取れていないことがある。
            loadJob = launcher.launch { fetchReferences(store.value.monthEvents(monthStart, kinds)) }
            return
        }

        update { it.copy(isLoading = true, isBackfilling = true) }
        val initialJob = launcher.launch {
            try {
                val coverage = store.value.coverage
                val monthReceivedLikes =
                    JournalFetchPlanner.willFetchMonthReceivedLikes(monthStart, today, kinds, coverage, targets)
                val initialMissing = coverage.missingByMember(nextSelectedDate, kinds, targets).let { missing ->
                    if (monthReceivedLikes) missing.withoutReceivedLikes() else missing
                }
                val results = fetchAll(
                    JournalFetchPlanner.forDate(nextSelectedDate, initialMissing, relayUrl, clock),
                )
                if (!isCurrent(token)) return@launch logStale("initial", token)
                val accepted = applyResults(results, replace = refresh)
                scheduleRelayRetries(results)
                update { it.copy(isLoading = false) }
                log { "initial done date=$nextSelectedDate ${store.value.summary()}" }
                fetchReferences(accepted)
                watchProfiles(targets)
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
            try {
                backfillMonth(token, kinds, today, replace = refresh)
            } finally {
                if (isCurrent(token)) update { it.copy(isBackfilling = false) }
            }
        }
    }

    /**
     * 月の残りの日を、日ごとではなく範囲で取る。件数の上限に届いたら、取り切れた日だけを反映して続きを取る。
     * 対象は始めた時点の表示中のメンバー。
     */
    private suspend fun backfillMonth(
        token: LoadToken,
        kinds: Set<JournalActivityKind>,
        today: LocalDate,
        replace: Boolean,
    ) {
        log { "backfill start month=${token.month}" }
        try {
            val current = store.value
            val requests = JournalFetchPlanner.forRange(
                token.month.daysOfMonthUntil(today), current.coverage, kinds, current.fetchMembers, relayUrl, clock,
            )
            for (request in requests) {
                val fetched = fetchPages(token, request, replace) ?: return
                scheduleRelayRetries(request, fetched.retryRelays)
            }
            log { "backfill done month=${token.month} ${store.value.summary()}" }
            // 参照先は日ごとではなく、月の分がそろってから1回で取る。返信の親やいいねの対象が
            // 同じ月の自分の投稿なら、取得済みの行から解決できる。
            fetchReferences(store.value.monthEvents(token.month, kinds))
            // 月一覧は名前がそろってから出す（16進のまま出て後から差し替わらないように）。待ちには上限がある。
            awaitWatchedProfiles()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log { "backfill failed: ${e::class.simpleName}: ${e.message}" }
            if (isCurrent(token)) {
                update { it.copy(isLoading = false, error = e.message ?: "ジャーナルの月間データの読み込みに失敗しました") }
            }
        }
    }

    /**
     * [request]の範囲を、打ち切られるたびに`until`を下げて取る。取り切れた日は取得済みにし（[replace]なら置き換え）、
     * 取り切れなかった日のイベントは足すだけにする。[token]の月を離れたら null。
     * [token]が null なら世代を見ない（取り直しは`JournalRelayRetries.cancelAll`で処理ごと止める）。
     */
    private suspend fun fetchPages(token: LoadToken?, request: JournalFetchRequest, replace: Boolean): PagedFetch? {
        val pager = JournalRangePager(request, clock)
        val accepted = mutableListOf<NostrEvent>()
        while (true) {
            val page = pager.next() ?: break
            val result = fetchAll(listOf(page)).single()
            if (token != null && !isCurrent(token)) {
                logStale("backfill ${request.kinds}", token)
                return null
            }
            val outcome = pager.accept(result)
            if (outcome.closedDates.isNotEmpty() || outcome.events.isNotEmpty()) {
                accepted += applyResults(
                    listOf(
                        JournalFetchResult(
                            request.copy(dates = outcome.closedDates),
                            outcome.events,
                            complete = true,
                            allRelaysFinished = outcome.allRelaysFinished,
                        ),
                    ),
                    replace,
                )
            }
        }
        val leftovers = pager.leftovers()
        if (leftovers.isNotEmpty()) {
            log { "backfill left ${leftovers.size} events unrecorded" }
            accepted += applyResults(
                listOf(JournalFetchResult(request.copy(dates = emptyList()), leftovers, complete = false)),
                replace,
            )
        }
        return PagedFetch(accepted, pager.retryRelays)
    }

    private class PagedFetch(val accepted: List<NostrEvent>, val retryRelays: Set<String>)

    private fun scheduleRelayRetries(results: List<JournalFetchResult>) {
        results.forEach { scheduleRelayRetries(it.request, it.retryRelays) }
    }

    /**
     * [relays]で取り終えなかった[request]を、そのリレーだけに送り直す。届いた分は足すだけにする（置き換えない）。
     * 取得済みの記録は、ほかのリレーで取り終えた時点で付いている。
     * 種類やメンバーを切り替えても続け、月の移動・リレーの切り替え・手動の更新でやめる（`loadMonth`）。
     */
    private fun scheduleRelayRetries(request: JournalFetchRequest, relays: Set<String>) {
        relays.forEach { url ->
            relayRetries.schedule(url) { retryUrl ->
                val fetched = fetchPages(null, request.copy(target = RelayTarget.Single(retryUrl)), replace = false)
                    ?: return@schedule true
                if (fetched.accepted.isNotEmpty()) {
                    log { "retry relay=$retryUrl kinds=${request.kinds} accepted=${fetched.accepted.size}" }
                    fetchReferences(fetched.accepted)
                }
                retryUrl !in fetched.retryRelays
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
        if (currentMembers() == null) return
        val token = LoadToken(monthGeneration, date.monthStart())
        val targets = current.fetchMembers
        val missing = if (force) {
            targets.associateWith { current.kinds }
        } else {
            current.coverage.missingByMember(date, current.kinds, targets)
        }
        log { "loadDate date=$date force=$force missing=$missing" }
        if (missing.isEmpty() || !hasConfiguredRelayUrl || !hasLoadedMembers) {
            update { it.copy(isLoading = false) }
            loadJob = launcher.launch {
                fetchReferences(store.value.timeline.entries(date, current.kinds).map { it.event })
            }
            return
        }
        update { it.copy(isLoading = true) }
        loadJob = launcher.launch {
            try {
                val results = fetchAll(JournalFetchPlanner.forDate(date, missing, relayUrl, clock))
                if (!isCurrent(token)) return@launch logStale("date $date", token)
                val accepted = applyResults(results, replace = force)
                scheduleRelayRetries(results)
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
                                "eventKinds=${request.filters.map { it.kinds }} until=${request.filters.firstOrNull()?.until} " +
                                "events=${result.events.size} complete=${result.complete} truncatedAt=${result.truncatedAt}"
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
     * [replace]なら、全リレーが完了し、打ち切られなかった取得の日付・種類だけを結果で置き換える。
     */
    private fun applyResults(
        results: List<JournalFetchResult>,
        replace: Boolean,
    ): List<NostrEvent> {
        // 取得中にメンバーを外しても取得は続くため、判定は結果が届いた時点のメンバーで行い、
        // 外した人の取得記録も残さない。
        val members = currentMembers() ?: return emptyList()
        // 分類は状態更新の外で1回だけ行う（更新関数は競合時に再実行されるため）。
        val accepted = results.map { result -> result to result.acceptedActivities(members) }
        update { state ->
            var timeline = state.timeline
            var coverage = state.coverage
            accepted.forEach { (result, activities) ->
                val request = result.request
                // 件数の上限で打ち切られた結果は、取り切れていない日があるため未完了と同じに扱う。
                val complete = result.complete && result.truncatedAt == null
                // 取り終えなかったリレーにしかない行を消さないよう、置き換えは全リレーが取り終えたときだけにする。
                if (replace && complete && result.allRelaysFinished) {
                    request.dates.forEach { date ->
                        timeline = timeline.removeMatching(date, request.kinds, request.members)
                    }
                }
                timeline = timeline.upsert(activities)
                if (complete) {
                    coverage = coverage.markLoaded(request.dates, request.kinds, request.members intersect members.pubkeys)
                }
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

    private fun JournalFetchResult.acceptedActivities(members: JournalMembers): List<JournalActivity> =
        events.mapNotNull { event ->
            // 種類は全メンバーで判定し、このリクエストのメンバーと種類に当たるものだけ採る。
            val roles = JournalActivityClassifier.classify(event, members)
            if (request.accepts(roles)) JournalActivity(event, clock.dateOf(event.createdAt), roles) else null
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

            // 取り直したリレーからも同じイベントが届くため、数えるのは1回だけにする。
            val counted = mutableSetOf<String>()
            var received = 0
            val status = engagementSource.fetch(noteIds, relayUrl) { event ->
                received += 1
                if (counted.add(event.id)) dirty += aggregator.add(event)
                emitProgress(force = false)
            }
            val snapshot = aggregator.snapshot()
            log {
                "engagement notes=${noteIds.size} events=$received withData=${snapshot.size} " +
                    "complete=${status.complete} allRelays=${status.allRelaysFinished} retry=${status.retryRelays} " +
                    "cachedNotes=${store.value.engagement.size}"
            }
            if (status.complete) {
                commitEngagement(noteIds, snapshot, status.allRelaysFinished)
                dirty.clear()
            } else {
                emitProgress(force = true)
            }
            scheduleEngagementRetries(noteIds, aggregator, counted, status.retryRelays)
            showEngagementDetails(snapshot)
        }
    }

    /**
     * 取得したエンゲージメントを反映する。全リレーが取り終えていれば結果で置き換え、そうでなければ足すだけにする。
     */
    private fun commitEngagement(
        noteIds: Set<String>,
        snapshot: Map<String, JournalNoteEngagement>,
        allRelaysFinished: Boolean,
    ) {
        update {
            it.copy(
                engagement = if (allRelaysFinished) {
                    it.engagement.replaceCompleted(noteIds, snapshot)
                } else {
                    it.engagement.mergeProgressive(snapshot)
                },
            )
        }
        loadedEngagementNoteIds += noteIds
    }

    /**
     * 取り終えなかったリレーだけにエンゲージメントを取り直し、最初の取得の集計に足して反映する。
     * 置き換えはしない。集計には最初の取得の後に表示へ入った分（確定した自分のいいね等）が含まれず、置き換えると消えるため。
     */
    private fun scheduleEngagementRetries(
        noteIds: Set<String>,
        aggregator: JournalEngagementAggregator,
        counted: MutableSet<String>,
        retryRelays: Set<String>,
    ) {
        retryRelays.forEach { url ->
            relayRetries.schedule(url) { retryUrl ->
                val retried = engagementSource.fetch(noteIds, retryUrl) { event ->
                    if (counted.add(event.id)) aggregator.add(event)
                }
                if (retried.complete) {
                    val snapshot = aggregator.snapshot()
                    commitEngagement(noteIds, snapshot, allRelaysFinished = false)
                    showEngagementDetails(snapshot)
                }
                retried.complete
            }
        }
    }

    private suspend fun showEngagementDetails(snapshot: Map<String, JournalNoteEngagement>) {
        fetchReferences(snapshot.values.flatMap { it.replies })
        watchProfiles(
            snapshot.values.flatMapTo(mutableSetOf()) { engagement ->
                engagement.reactionEvents.map { it.pubkey } + engagement.repostPubkeys
            },
        )
    }

    /**
     * 行の表示に必要な参照先を用意し、作者と参照先の作者のプロフィールを監視に加える。
     * ジャーナルに取得済みの行はそのまま使い、残りだけを指定のリレー優先で取得する。
     */
    private suspend fun fetchReferences(events: Collection<NostrEvent>) {
        if (events.isEmpty()) return
        val current = store.value
        val ids = events.flatMapTo(mutableSetOf()) { it.journalReferencedEventIds() } - current.referencedEvents.keys
        val inTimeline = ids.mapNotNull { id -> current.timeline[id]?.event }
        if (inTimeline.isNotEmpty()) {
            update { it.copy(referencedEvents = it.referencedEvents + inTimeline.associateBy(NostrEvent::id)) }
        }
        val missing = ids - inTimeline.mapTo(mutableSetOf(), NostrEvent::id)
        val fetched = mutableListOf<NostrEvent>()
        if (missing.isNotEmpty()) {
            try {
                referenceSource.fetch(missing, relayUrl) { event -> fetched += event }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // 取得できなかった参照先は行の側で「読み込み中」と表示する。
            }
            if (fetched.isNotEmpty()) {
                update { it.copy(referencedEvents = it.referencedEvents + fetched.associateBy(NostrEvent::id)) }
            }
        }
        if (ids.isNotEmpty()) {
            log {
                "references requested=${ids.size} inTimeline=${inTimeline.size} fetched=${fetched.size}/${missing.size} " +
                    "cached=${store.value.referencedEvents.size}"
            }
        }
        watchProfiles(events.mapTo(mutableSetOf()) { it.pubkey } + (inTimeline + fetched).map { it.pubkey })
    }

    /** 監視中の人のプロフィールがそろうのを[REVEAL_PROFILE_WAIT_MS]まで待つ。取得はその後も続く。 */
    private suspend fun awaitWatchedProfiles() {
        val pubkeys = watchedPubkeys.toSet()
        if (pubkeys.isEmpty() || store.value.profiles.keys.containsAll(pubkeys)) return
        withTimeoutOrNull(REVEAL_PROFILE_WAIT_MS) {
            profileSource.observe(pubkeys).firstOrNull { profiles -> (store.value.profiles.keys + profiles.keys).containsAll(pubkeys) }
        }?.let { profiles -> update { it.copy(profiles = it.profiles + profiles) } }
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
    "timeline=${timeline.size} coveredDays=${coverage.coveredDays} refs=${referencedEvents.size} " +
        "engagement=${engagement.size} profiles=${profiles.size} month=$selectedMonth selected=$selectedDate"

/** 表示中の[members]が、選択中の月の選択中の種類をすべて取得し終えたか。 */
private fun JournalState.hasLoadedMonthFor(members: Set<String>, today: LocalDate): Boolean =
    coverage.hasLoadedMonth(selectedMonth, kinds, today, members)

private fun Map<String, Set<JournalActivityKind>>.withoutReceivedLikes(): Map<String, Set<JournalActivityKind>> =
    mapValues { (_, kinds) -> kinds - JournalActivityKind.ReceivedLike }.filterValues { it.isNotEmpty() }

/** 選択中の月の[kinds]に当たる行のイベント。メンバーは非表示の人も含める。 */
private fun JournalState.monthEvents(month: LocalDate, kinds: Set<JournalActivityKind>): List<NostrEvent> =
    timeline.monthEntries(month, kinds).map { it.event }

private fun JournalState.datesWithEntries(month: LocalDate): List<LocalDate> =
    timeline.datesWithEntries(month, kinds, visibleMembers)

private fun JournalState.withSummary(
    eventId: String,
    summary: NoteEngagementState,
): JournalState = copy(engagement = engagement + (eventId to engagementOf(eventId).copy(summary = summary)))

private fun JournalState.withDeleteError(message: String): JournalState =
    copy(noteDeleteDialog = noteDeleteDialog?.copy(isDeleting = false, error = message))

private const val ENGAGEMENT_STATE_BATCH_MS = 100L
private const val PROFILE_MAX_AGE_MS = 15 * 60 * 1_000L
private const val REVEAL_PROFILE_WAIT_MS = 1_000L
private const val CANDIDATE_LOOKBACK_SECONDS = 30 * 24 * 60 * 60L
private const val CANDIDATE_LIMIT = 30
private const val CANDIDATE_CACHE_MS = 5 * 60 * 1_000L
/** 取得の途中で候補を並べ直す間隔。短すぎると並びが細かく動き、長すぎると最初の表示が遅れる。 */
private const val CANDIDATE_PARTIAL_INTERVAL_MS = 200L
