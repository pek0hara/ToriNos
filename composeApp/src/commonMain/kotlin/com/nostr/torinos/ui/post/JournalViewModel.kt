package com.nostr.torinos.ui.post

import com.nostr.torinos.crypto.KeyStorage
import com.nostr.torinos.crypto.Nip44
import com.nostr.torinos.crypto.derivePublicKey
import com.nostr.torinos.crypto.fromHex
import com.nostr.torinos.crypto.signEvent
import com.nostr.torinos.crypto.toHex
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.quotedEventIds
import com.nostr.torinos.model.replyTargetId
import com.nostr.torinos.model.toProfile
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.ui.SafeViewModel
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime

data class JournalItem(
    val eventId: String,
    val pubkey: String,
    val tags: List<List<String>>,
    val memo: PostMemoData,
    val createdAt: Long,
) {
    val displayTime: Long get() = memo.updatedAt.takeIf { it > 0 } ?: createdAt
}

sealed class JournalEntry {
    data class Memo(val item: JournalItem) : JournalEntry()
    data class Note(val event: NostrEvent, val profile: NostrProfile?) : JournalEntry()

    val displayTime: Long get() = when (this) {
        is Memo -> item.displayTime
        is Note -> event.createdAt
    }
}

data class JournalDeleteDialogState(
    val item: JournalItem,
    val isDeleting: Boolean = false,
    val error: String? = null,
)

data class JournalState(
    val selectedMonth: LocalDate = currentMonth(),
    val selectedDate: LocalDate = currentDate(),
    val showCalendar: Boolean = true,
    val memos: List<JournalItem> = emptyList(),
    val notes: List<NostrEvent> = emptyList(),
    val profiles: Map<String, NostrProfile> = emptyMap(),
    val quotedEvents: Map<String, NostrEvent> = emptyMap(),
    val reactionCounts: Map<String, Int> = emptyMap(),
    val replyCounts: Map<String, Int> = emptyMap(),
    val repostCounts: Map<String, Int> = emptyMap(),
    val likedReactions: Map<String, String> = emptyMap(),
    val isLoading: Boolean = false,
    val deleteDialog: JournalDeleteDialogState? = null,
    val error: String? = null,
) {
    private val memosByDate: Map<LocalDate, List<JournalItem>> =
        memos.groupBy { dateOfEpochSeconds(it.displayTime) }

    private val notesByDate: Map<LocalDate, List<NostrEvent>> =
        notes.groupBy { dateOfEpochSeconds(it.createdAt) }

    val entryCountsByDate: Map<LocalDate, Int> = buildMap {
        (memosByDate.keys + notesByDate.keys).distinct().forEach { date ->
            put(date, (memosByDate[date]?.size ?: 0) + (notesByDate[date]?.size ?: 0))
        }
    }

    val selectedEntries: List<JournalEntry> = buildList {
        memosByDate[selectedDate].orEmpty().forEach { add(JournalEntry.Memo(it)) }
        notesByDate[selectedDate].orEmpty().forEach { add(JournalEntry.Note(it, profiles[it.pubkey])) }
    }.sortedBy { it.displayTime }

    val monthEntries: List<JournalEntry> = buildList {
        memos.forEach { add(JournalEntry.Memo(it)) }
        notes.forEach { add(JournalEntry.Note(it, profiles[it.pubkey])) }
    }.sortedBy { it.displayTime }

    val canGoNextMonth: Boolean get() = selectedMonth < currentMonth()
    val canGoNextDate: Boolean get() = selectedDate < currentDate()
}

class JournalViewModel(private val targetPubkey: String? = null) : SafeViewModel() {
    private val _state = MutableStateFlow(JournalState())
    val state: StateFlow<JournalState> = _state.asStateFlow()
    private var loadJob: Job? = null
    private var monthBackfillJob: Job? = null
    private var engagementJob: Job? = null
    private var referencedContentJob: Job? = null
    private var relayUrl: String? = null
    private var hasConfiguredRelayUrl = false
    private var ownPublicKeyHex: String? = null

    fun selectDate(date: LocalDate) {
        _state.value = _state.value.copy(selectedDate = date)
        loadDate(date)
    }

    fun previousDate() {
        selectDateOrLoadMonth(_state.value.selectedDate.minusDays(1))
    }

    fun nextDate() {
        val next = _state.value.selectedDate.plusDays(1)
        if (next <= currentDate()) {
            selectDateOrLoadMonth(next)
        }
    }

    fun toggleCalendar() {
        _state.value = _state.value.copy(showCalendar = !_state.value.showCalendar)
    }

    fun showCalendar() {
        if (!_state.value.showCalendar) {
            _state.value = _state.value.copy(showCalendar = true)
        }
    }

    fun previousMonth() {
        loadMonth(_state.value.selectedMonth.previousMonth())
    }

    fun nextMonth() {
        val next = _state.value.selectedMonth.nextMonth()
        if (next <= currentMonth()) {
            loadMonth(next)
        }
    }

    fun selectMonth(year: Int, month: Int) {
        if (year <= 0 || month !in 1..12) return
        val requestedMonth = LocalDate(year, month, 1)
        val targetMonth = minOf(requestedMonth, currentMonth())
        loadMonth(targetMonth)
    }

    fun refreshToday() {
        val today = currentDate()
        loadMonth(today.monthStart(), selectedDate = today)
    }

    fun refresh() {
        loadMonth(_state.value.selectedMonth)
    }

    fun setRelayUrl(url: String?) {
        if (hasConfiguredRelayUrl && relayUrl == url) return
        hasConfiguredRelayUrl = true
        relayUrl = url
        loadMonth(_state.value.selectedMonth)
    }

    private fun selectDateOrLoadMonth(date: LocalDate) {
        val currentMonth = _state.value.selectedMonth
        if (date.year == currentMonth.year && date.month == currentMonth.month) {
            selectDate(date)
        } else {
            loadMonth(date.monthStart(), selectedDate = date)
        }
    }

    fun react(eventId: String, eventPubkey: String) {
        val cur = _state.value
        if (cur.likedReactions.containsKey(eventId)) return
        _state.value = cur.copy(
            likedReactions = cur.likedReactions + (eventId to ""),
            reactionCounts = cur.reactionCounts + (eventId to (cur.reactionCounts[eventId] ?: 0) + 1),
        )
        launch {
            val privateKeyHex = KeyStorage.loadPrivateKey() ?: return@launch
            runCatching {
                val reaction = signEvent(
                    privateKeyHex = privateKeyHex,
                    content = "+",
                    kind = 7,
                    tags = listOf(listOf("e", eventId), listOf("p", eventPubkey)),
                )
                NostrRepository.publish(reaction)
                _state.value = _state.value.copy(
                    likedReactions = _state.value.likedReactions + (eventId to reaction.id),
                )
            }
        }
    }

    fun unreact(eventId: String) {
        val cur = _state.value
        val reactionEventId = cur.likedReactions[eventId] ?: return
        _state.value = cur.copy(
            likedReactions = cur.likedReactions - eventId,
            reactionCounts = cur.reactionCounts + (eventId to maxOf(0, (cur.reactionCounts[eventId] ?: 0) - 1)),
        )
        if (reactionEventId.isEmpty()) return
        launch {
            val privateKeyHex = KeyStorage.loadPrivateKey() ?: return@launch
            runCatching {
                val deletion = signEvent(
                    privateKeyHex = privateKeyHex,
                    content = "",
                    kind = 5,
                    tags = listOf(listOf("e", reactionEventId)),
                )
                NostrRepository.publish(deletion)
            }
        }
    }

    fun showDeleteDialog(item: JournalItem) {
        _state.value = _state.value.copy(
            deleteDialog = JournalDeleteDialogState(item = item),
        )
    }

    fun dismissDeleteDialog() {
        if (_state.value.deleteDialog?.isDeleting == true) return
        _state.value = _state.value.copy(deleteDialog = null)
    }

    fun deleteSelectedMemo() {
        val dialog = _state.value.deleteDialog ?: return
        _state.value = _state.value.copy(
            deleteDialog = dialog.copy(isDeleting = true, error = null),
        )
        launch {
            val privateKeyHex = KeyStorage.loadPrivateKey() ?: run {
                _state.value = _state.value.copy(
                    deleteDialog = _state.value.deleteDialog?.copy(
                        isDeleting = false,
                        error = "秘密鍵が設定されていません",
                    ),
                )
                return@launch
            }

            runCatching {
                val deletion = signEvent(
                    privateKeyHex = privateKeyHex,
                    content = "",
                    kind = 5,
                    tags = buildList {
                        add(listOf("e", dialog.item.eventId))
                        dialog.item.addressTagValue()?.let { add(listOf("a", it)) }
                        add(listOf("k", MEMO_EVENT_KIND.toString()))
                        add(listOf("client", "ToriNos"))
                    },
                )
                NostrRepository.publish(deletion)
            }.onSuccess {
                _state.value = _state.value.copy(
                    memos = _state.value.memos.filterNot { it.eventId == dialog.item.eventId },
                    deleteDialog = null,
                )
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    deleteDialog = _state.value.deleteDialog?.copy(
                        isDeleting = false,
                        error = e.message ?: "ポストメモの削除要求を送信できませんでした",
                    ),
                )
            }
        }
    }

    private fun loadMonth(
        month: LocalDate,
        selectedDate: LocalDate? = null,
    ) {
        val monthStart = month.monthStart()
        val nextSelectedDate = selectedDate
            ?.takeIf { it.year == monthStart.year && it.month == monthStart.month }
            ?: _state.value.selectedDate
            .takeIf { it.year == monthStart.year && it.month == monthStart.month }
            ?: monthStart
        _state.value = _state.value.copy(
            selectedMonth = monthStart,
            selectedDate = nextSelectedDate,
            isLoading = true,
            error = null,
        )

        referencedContentJob?.cancel()
        engagementJob?.cancel()
        monthBackfillJob?.cancel()
        loadJob?.cancel()
        loadJob = launch {
            try {
                val context = resolveLoadContext() ?: return@launch

                val (memoEvents, noteEvents, profile) = coroutineScope {
                    val eventsDeferred = async {
                        fetchEvents(
                            pubkey = context.publicKeyHex,
                            since = nextSelectedDate.startOfDayEpochSeconds(),
                            until = nextSelectedDate.plusDays(1).startOfDayEpochSeconds() - 1,
                            relayUrl = relayUrl,
                            includeMemos = targetPubkey == null,
                            includeLikes = targetPubkey == null,
                            limit = JOURNAL_DATE_LIMIT,
                        )
                    }
                    val profileDeferred = async {
                        if (_state.value.profiles.containsKey(context.publicKeyHex)) {
                            _state.value.profiles[context.publicKeyHex]
                        } else {
                            fetchProfile(context.publicKeyHex, relayUrl)
                        }
                    }
                    val (memos, notes) = eventsDeferred.await()
                    Triple(memos, notes, profileDeferred.await())
                }

                val memos = decodeMemoEvents(memoEvents, context)

                val profiles = if (profile != null) {
                    _state.value.profiles + (context.publicKeyHex to profile)
                } else {
                    _state.value.profiles
                }

                _state.value = _state.value.copy(
                    isLoading = false,
                    memos = memos,
                    notes = noteEvents.sortedByDescending { it.createdAt },
                    profiles = profiles,
                    quotedEvents = emptyMap(),
                    reactionCounts = emptyMap(),
                    replyCounts = emptyMap(),
                    repostCounts = emptyMap(),
                    likedReactions = emptyMap(),
                    error = null,
                )

                val noteIds = noteEvents.map { it.id }
                if (noteIds.isNotEmpty()) {
                    fetchEngagement(noteIds, ownPublicKeyHex)
                }
                fetchReferencedContent(noteEvents, memos, relayUrl)
                backfillMonthEntries(
                    context = context,
                    monthStart = monthStart,
                    loadedDate = nextSelectedDate,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    memos = emptyList(),
                    notes = emptyList(),
                    error = e.message ?: "ポストメモの読み込みに失敗しました",
                )
            }
        }
    }

    private fun backfillMonthEntries(
        context: JournalLoadContext,
        monthStart: LocalDate,
        loadedDate: LocalDate,
    ) {
        monthBackfillJob?.cancel()
        monthBackfillJob = launch {
            try {
                val (memoEvents, noteEvents) = fetchMonthEvents(
                    pubkey = context.publicKeyHex,
                    monthStart = monthStart,
                    relayUrl = relayUrl,
                    includeMemos = targetPubkey == null,
                    includeLikes = targetPubkey == null,
                    excludeDates = setOf(loadedDate),
                )
                if (_state.value.selectedMonth != monthStart) return@launch

                val memos = decodeMemoEvents(memoEvents, context)
                _state.value = _state.value.copy(
                    memos = (_state.value.memos + memos)
                        .distinctBy { it.eventId }
                        .sortedByDescending { it.displayTime },
                    notes = (_state.value.notes + noteEvents)
                        .distinctBy { it.id }
                        .sortedByDescending { it.createdAt },
                )
            } catch (e: CancellationException) {
                throw e
            }
        }
    }

    private fun loadDate(date: LocalDate) {
        referencedContentJob?.cancel()
        engagementJob?.cancel()
        loadJob?.cancel()
        loadJob = launch {
            try {
                val context = resolveLoadContext() ?: return@launch
                _state.value = _state.value.copy(
                    selectedMonth = date.monthStart(),
                    selectedDate = date,
                    isLoading = true,
                    error = null,
                )
                val (memoEvents, noteEvents) = fetchEvents(
                    pubkey = context.publicKeyHex,
                    since = date.startOfDayEpochSeconds(),
                    until = date.plusDays(1).startOfDayEpochSeconds() - 1,
                    relayUrl = relayUrl,
                    includeMemos = targetPubkey == null,
                    includeLikes = targetPubkey == null,
                    limit = JOURNAL_DATE_LIMIT,
                )
                val memos = decodeMemoEvents(memoEvents, context)
                val notes = noteEvents.sortedByDescending { it.createdAt }
                _state.value = _state.value.copy(
                    isLoading = false,
                    memos = replaceMemosForDate(_state.value.memos, date, memos),
                    notes = replaceNotesForDate(_state.value.notes, date, notes),
                    error = null,
                )

                val noteIds = notes.map { it.id }
                if (noteIds.isNotEmpty()) {
                    fetchEngagement(noteIds, ownPublicKeyHex)
                }
                fetchReferencedContent(notes, memos, relayUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = e.message ?: "この日の投稿の読み込みに失敗しました",
                )
            }
        }
    }

    private suspend fun fetchMonthEvents(
        pubkey: String,
        monthStart: LocalDate,
        relayUrl: String? = null,
        includeMemos: Boolean = true,
        includeLikes: Boolean = true,
        excludeDates: Set<LocalDate> = emptySet(),
    ): Pair<List<NostrEvent>, List<NostrEvent>> = coroutineScope {
        val lastDay = minOf(monthStart.nextMonth().minusDays(1), currentDate())
        val dates = generateSequence(monthStart) { it.plusDays(1) }
            .takeWhile { it <= lastDay }
            .filterNot { it in excludeDates }
            .toList()
        val dailyResults = dates.map { date ->
            async {
                fetchEvents(
                    pubkey = pubkey,
                    since = date.startOfDayEpochSeconds(),
                    until = date.plusDays(1).startOfDayEpochSeconds() - 1,
                    relayUrl = relayUrl,
                    includeMemos = includeMemos,
                    includeLikes = includeLikes,
                    limit = JOURNAL_MONTH_DAY_LIMIT,
                )
            }
        }.map { it.await() }

        Pair(
            dailyResults.flatMap { it.first }.distinctBy { it.id },
            dailyResults.flatMap { it.second }.distinctBy { it.id },
        )
    }

    private suspend fun fetchEvents(
        pubkey: String,
        since: Long,
        until: Long,
        relayUrl: String? = null,
        includeMemos: Boolean = true,
        includeLikes: Boolean = true,
        limit: Int = JOURNAL_DATE_LIMIT,
    ): Pair<List<NostrEvent>, List<NostrEvent>> = coroutineScope {
        val noteKinds = if (includeLikes) listOf(1, 6, 7) else listOf(1, 6)
        val subId = "journal-${Clock.System.now().epochSeconds}-${since}"
        val mutex = Mutex()
        val memoEvents = mutableListOf<NostrEvent>()
        val noteEvents = mutableListOf<NostrEvent>()
        var collector: Job? = null
        try {
            collector = launch {
                NostrRepository.events(subId).collect { event ->
                    when {
                        includeMemos && event.kind == MEMO_EVENT_KIND && event.pubkey == pubkey ->
                            mutex.withLock { memoEvents += event }
                        event.kind in noteKinds && event.pubkey == pubkey ->
                            mutex.withLock { noteEvents += event }
                    }
                }
            }
            NostrRepository.subscribe(
                subId,
                NostrFilter(
                    kinds = if (includeMemos) listOf(MEMO_EVENT_KIND) + noteKinds else noteKinds,
                    authors = listOf(pubkey),
                    since = since,
                    until = until,
                    limit = limit,
                ),
                relayUrl = relayUrl,
            )
            withTimeoutOrNull(MEMO_FETCH_TIMEOUT_MS) {
                NostrRepository.eose(subId).first()
            }
            mutex.withLock {
                Pair(memoEvents.distinctBy { it.id }, noteEvents.distinctBy { it.id })
            }
        } finally {
            runCatching { NostrRepository.close(subId) }
            collector?.cancelAndJoin()
        }
    }

    private suspend fun fetchProfile(
        pubkey: String,
        relayUrl: String? = null,
    ): NostrProfile? = coroutineScope {
        val subId = "memo-prof-${pubkey.take(16)}-${Clock.System.now().epochSeconds}"
        var result: NostrProfile? = null
        var collector: Job? = null
        try {
            collector = launch {
                NostrRepository.events(subId).collect { event ->
                    if (event.kind == 0 && event.pubkey == pubkey) {
                        result = event.toProfile()
                    }
                }
            }
            NostrRepository.subscribe(
                subId,
                NostrFilter(kinds = listOf(0), authors = listOf(pubkey), limit = 1),
                relayUrl = relayUrl,
            )
            withTimeoutOrNull(5_000L) {
                NostrRepository.eose(subId).first()
            }
            result
        } finally {
            runCatching { NostrRepository.close(subId) }
            collector?.cancelAndJoin()
        }
    }

    private fun fetchEngagement(noteIds: List<String>, ownPubkey: String?) {
        engagementJob?.cancel()
        engagementJob = launch {
            val subId = "memo-engage-${Clock.System.now().epochSeconds}"
            val noteIdSet = noteIds.toHashSet()
            val mutex = Mutex()
            val reactionCounts = mutableMapOf<String, Int>()
            val replyCounts = mutableMapOf<String, Int>()
            val repostCounts = mutableMapOf<String, Int>()
            val likedReactions = mutableMapOf<String, String>()
            var collector: Job? = null
            try {
                collector = launch {
                    NostrRepository.events(subId).collect { event ->
                        val targetId = event.tags.firstOrNull { it.firstOrNull() == "e" }
                            ?.getOrNull(1) ?: return@collect
                        if (targetId !in noteIdSet) return@collect
                        mutex.withLock {
                            when (event.kind) {
                                7 -> {
                                    reactionCounts[targetId] = (reactionCounts[targetId] ?: 0) + 1
                                    if (event.pubkey == ownPubkey && !likedReactions.containsKey(targetId)) {
                                        likedReactions[targetId] = event.id
                                    }
                                }
                                1 -> replyCounts[targetId] = (replyCounts[targetId] ?: 0) + 1
                                6 -> repostCounts[targetId] = (repostCounts[targetId] ?: 0) + 1
                            }
                        }
                    }
                }
                NostrRepository.subscribe(
                    subId,
                    NostrFilter(kinds = listOf(1, 6, 7), eTags = noteIds, limit = 500),
                    relayUrl = relayUrl,
                )
                withTimeoutOrNull(8_000L) {
                    NostrRepository.eose(subId).first()
                }
                mutex.withLock {
                    _state.value = _state.value.copy(
                        reactionCounts = reactionCounts,
                        replyCounts = replyCounts,
                        repostCounts = repostCounts,
                        likedReactions = likedReactions,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                runCatching { NostrRepository.close(subId) }
                collector?.cancelAndJoin()
            }
        }
    }

    private data class JournalLoadContext(
        val privateKeyHex: String?,
        val publicKeyHex: String,
    )

    private suspend fun resolveLoadContext(): JournalLoadContext? {
        val privateKeyHex = if (targetPubkey == null) {
            KeyStorage.loadPrivateKey() ?: run {
                _state.value = _state.value.copy(
                    isLoading = false,
                    memos = emptyList(),
                    notes = emptyList(),
                    error = "秘密鍵が設定されていません",
                )
                return null
            }
        } else {
            null
        }
        val publicKeyHex = targetPubkey ?: derivePublicKey(privateKeyHex!!.fromHex()).toHex()
        ownPublicKeyHex = privateKeyHex?.let { derivePublicKey(it.fromHex()).toHex() }
        return JournalLoadContext(privateKeyHex = privateKeyHex, publicKeyHex = publicKeyHex)
    }

    private fun decodeMemoEvents(
        events: List<NostrEvent>,
        context: JournalLoadContext,
    ): List<JournalItem> {
        val privateKeyHex = context.privateKeyHex ?: return emptyList()
        return events.mapNotNull { event ->
            decodeMemo(event, privateKeyHex, context.publicKeyHex)?.let { memo ->
                JournalItem(
                    eventId = event.id,
                    pubkey = event.pubkey,
                    tags = event.tags,
                    memo = memo.toPostMemoData(identifier = event.memoIdentifier()),
                    createdAt = event.createdAt,
                )
            }
        }.sortedByDescending { it.displayTime }
    }

    private fun replaceMemosForDate(
        current: List<JournalItem>,
        date: LocalDate,
        replacement: List<JournalItem>,
    ): List<JournalItem> =
        (current.filterNot { dateOfEpochSeconds(it.displayTime) == date } + replacement)
            .distinctBy { it.eventId }
            .sortedByDescending { it.displayTime }

    private fun replaceNotesForDate(
        current: List<NostrEvent>,
        date: LocalDate,
        replacement: List<NostrEvent>,
    ): List<NostrEvent> =
        (current.filterNot { dateOfEpochSeconds(it.createdAt) == date } + replacement)
            .distinctBy { it.id }
            .sortedByDescending { it.createdAt }

    private fun fetchReferencedContent(notes: List<NostrEvent>, memos: List<JournalItem>, relayUrl: String?) {
        referencedContentJob?.cancel()
        referencedContentJob = launch {
            try {
                val eventIdsToFetch = buildSet {
                    notes.forEach { event ->
                        event.replyTargetId()?.let { add(it) }
                        addAll(quotedEventIds(event))
                        event.activityTargetId()?.let { add(it) }
                    }
                }
                val memoPubkeys = memos.mapNotNull { it.memo.replyToPubkey }.toHashSet()

                val fetchedEvents = mutableListOf<NostrEvent>()

                if (eventIdsToFetch.isNotEmpty()) {
                    val subId = "memo-refs-${Clock.System.now().epochSeconds}"
                    val mutex = Mutex()
                    var collector: Job? = null
                    try {
                        collector = launch {
                            NostrRepository.events(subId).collect { event ->
                                if (event.id in eventIdsToFetch) {
                                    mutex.withLock { fetchedEvents += event }
                                }
                            }
                        }
                        NostrRepository.subscribe(
                            subId,
                            NostrFilter(ids = eventIdsToFetch.toList(), kinds = listOf(1), limit = eventIdsToFetch.size),
                            relayUrl = relayUrl,
                        )
                        withTimeoutOrNull(5_000L) {
                            NostrRepository.eose(subId).first()
                        }
                        _state.value = _state.value.copy(
                            quotedEvents = fetchedEvents.associateBy { it.id },
                        )
                    } finally {
                        runCatching { NostrRepository.close(subId) }
                        collector?.cancelAndJoin()
                    }
                }

                val pubkeysToFetch = buildSet {
                    addAll(memoPubkeys)
                    fetchedEvents.forEach { add(it.pubkey) }
                }.filterNot { _state.value.profiles.containsKey(it) }

                if (pubkeysToFetch.isNotEmpty()) {
                    val subId = "memo-ref-prof-${Clock.System.now().epochSeconds}"
                    val mutex = Mutex()
                    val newProfiles = mutableMapOf<String, NostrProfile>()
                    val pubkeySet = pubkeysToFetch.toHashSet()
                    var collector: Job? = null
                    try {
                        collector = launch {
                            NostrRepository.events(subId).collect { event ->
                                if (event.kind == 0 && event.pubkey in pubkeySet) {
                                    event.toProfile()?.let { profile ->
                                        mutex.withLock { newProfiles[event.pubkey] = profile }
                                    }
                                }
                            }
                        }
                        NostrRepository.subscribe(
                            subId,
                            NostrFilter(kinds = listOf(0), authors = pubkeysToFetch, limit = pubkeysToFetch.size),
                            relayUrl = relayUrl,
                        )
                        withTimeoutOrNull(5_000L) {
                            NostrRepository.eose(subId).first()
                        }
                        if (newProfiles.isNotEmpty()) {
                            _state.value = _state.value.copy(
                                profiles = _state.value.profiles + newProfiles,
                            )
                        }
                    } finally {
                        runCatching { NostrRepository.close(subId) }
                        collector?.cancelAndJoin()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            }
        }
    }

    private fun decodeMemo(
        event: NostrEvent,
        privateKeyHex: String,
        publicKeyHex: String,
    ): PostMemoPayload? =
        runCatching {
            memoJson.decodeFromString<PostMemoPayload>(
                Nip44.decrypt(event.content, privateKeyHex, publicKeyHex),
            )
        }.getOrNull()
}

private fun NostrEvent.memoIdentifier(): String? =
    tags.firstOrNull { it.firstOrNull() == "d" }?.getOrNull(1)

private fun NostrEvent.activityTargetId(): String? =
    tags.lastOrNull { it.firstOrNull() == "e" }?.getOrNull(1)
        ?: embeddedRepostTarget()?.id

private fun NostrEvent.embeddedRepostTarget(): NostrEvent? {
    if (kind != 6 || content.isBlank()) return null
    return runCatching {
        Json.decodeFromString(NostrEvent.serializer(), content)
    }.getOrNull()
}

private fun JournalItem.addressTagValue(): String? {
    val d = memo.identifier
        ?: return null
    return "$MEMO_EVENT_KIND:$pubkey:$d"
}

internal fun currentDate(): LocalDate =
    Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

internal fun currentMonth(): LocalDate =
    currentDate().monthStart()

internal fun currentFourWeekStart(): LocalDate =
    currentDate().weekStart().minusDays(21)

internal fun dateOfEpochSeconds(epochSeconds: Long): LocalDate =
    Instant.fromEpochSeconds(epochSeconds)
        .toLocalDateTime(TimeZone.currentSystemDefault())
        .date

internal fun LocalDate.monthStart(): LocalDate =
    LocalDate(year, month, 1)

internal fun LocalDate.nextMonth(): LocalDate =
    if (month.ordinal == 11) LocalDate(year + 1, 1, 1) else LocalDate(year, month.ordinal + 2, 1)

internal fun LocalDate.previousMonth(): LocalDate =
    if (month.ordinal == 0) LocalDate(year - 1, 12, 1) else LocalDate(year, month.ordinal, 1)

internal fun LocalDate.weekStart(): LocalDate =
    minusDays((dayOfWeek.ordinal + 1) % 7)

internal fun LocalDate.plusDays(days: Int): LocalDate =
    dateOfEpochSeconds(startOfDayEpochSeconds() + days * 86_400L)

internal fun LocalDate.minusDays(days: Int): LocalDate =
    plusDays(-days)

internal fun LocalDate.startOfDayEpochSeconds(): Long =
    atStartOfDayIn(TimeZone.currentSystemDefault()).epochSeconds

private const val JOURNAL_MONTH_DAY_LIMIT = 10
private const val JOURNAL_DATE_LIMIT = 500
