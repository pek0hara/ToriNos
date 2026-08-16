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
import com.nostr.torinos.model.NIP23_ARTICLE_KIND
import com.nostr.torinos.model.CustomReaction
import com.nostr.torinos.model.ReactionOption
import com.nostr.torinos.model.UnicodeReaction
import com.nostr.torinos.model.decrementedWith
import com.nostr.torinos.model.decrementedWithUnicodeReaction
import com.nostr.torinos.model.eventTags
import com.nostr.torinos.model.incrementedWith
import com.nostr.torinos.model.incrementedWithUnicodeReaction
import com.nostr.torinos.model.quotedEventIds
import com.nostr.torinos.model.replyTargetId
import com.nostr.torinos.model.toCustomReaction
import com.nostr.torinos.model.toUnicodeReaction
import com.nostr.torinos.model.toReactionOption
import com.nostr.torinos.model.toProfile
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.ui.SafeViewModel
import com.nostr.torinos.util.appLog
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.random.Random

data class JournalItem(
    val eventId: String,
    val pubkey: String,
    val tags: List<List<String>>,
    val memo: PostMemoData,
    val createdAt: Long,
) {
    val displayTime: Long get() = memo.updatedAt.takeIf { it > 0 } ?: createdAt
}

private fun JournalState.withOptimisticEmojiReaction(
    eventId: String,
    option: ReactionOption,
    reactionEventId: String,
): JournalState = copy(
    reactionCounts = reactionCounts + (eventId to (reactionCounts[eventId] ?: 0) + 1),
    customReactions = if (option is ReactionOption.Custom) {
        customReactions + (
            eventId to customReactions[eventId].orEmpty().incrementedWith(
                CustomReaction(option.shortcode.trim().trim(':'), option.imageUrl.trim()),
            )
            )
    } else {
        customReactions
    },
    unicodeReactions = if (option is ReactionOption.Unicode) {
        unicodeReactions + (
            eventId to unicodeReactions[eventId].orEmpty().incrementedWithUnicodeReaction(
                UnicodeReaction(option.value.trim()),
            )
            )
    } else {
        unicodeReactions
    },
    ownEmojiReactionEventIds = ownEmojiReactionEventIds + (
        eventId to ownEmojiReactionEventIds[eventId].orEmpty()
            .plus(option.key to reactionEventId)
        ),
)

private fun JournalState.withoutOptimisticLike(eventId: String): JournalState {
    if (likedReactions[eventId] != "") return this
    return copy(
        likedReactions = likedReactions - eventId,
        reactionCounts = reactionCounts + (
            eventId to maxOf(0, (reactionCounts[eventId] ?: 0) - 1)
            ),
        likeReactionCounts = likeReactionCounts + (
            eventId to maxOf(0, (likeReactionCounts[eventId] ?: 0) - 1)
            ),
    )
}

private fun JournalState.withoutOptimisticEmojiReaction(
    eventId: String,
    option: ReactionOption,
): JournalState {
    val remaining = ownEmojiReactionEventIds[eventId].orEmpty() - option.key
    return copy(
        reactionCounts = reactionCounts + (
            eventId to maxOf(0, (reactionCounts[eventId] ?: 0) - 1)
            ),
        customReactions = if (option is ReactionOption.Custom) {
            customReactions + (
                eventId to customReactions[eventId].orEmpty().decrementedWith(option)
                )
        } else {
            customReactions
        },
        unicodeReactions = if (option is ReactionOption.Unicode) {
            unicodeReactions + (
                eventId to unicodeReactions[eventId].orEmpty()
                    .decrementedWithUnicodeReaction(option)
                )
        } else {
            unicodeReactions
        },
        ownEmojiReactionEventIds = if (remaining.isEmpty()) {
            ownEmojiReactionEventIds - eventId
        } else {
            ownEmojiReactionEventIds + (eventId to remaining)
        },
    )
}

sealed class JournalEntry {
    data class Memo(val item: JournalItem) : JournalEntry()
    data class Note(val event: NostrEvent, val profile: NostrProfile?) : JournalEntry()

    val displayTime: Long get() = when (this) {
        is Memo -> item.displayTime
        is Note -> event.createdAt
    }
}

enum class JournalLoadKind {
    Post,
    Reply,
    Repost,
    Like,
    Memo,
    Article,
}

data class JournalDeleteDialogState(
    val item: JournalItem,
    val isDeleting: Boolean = false,
    val error: String? = null,
)

data class JournalNoteDeleteDialogState(
    val event: NostrEvent,
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
    val likeReactionCounts: Map<String, Int> = emptyMap(),
    val customReactions: Map<String, List<CustomReaction>> = emptyMap(),
    val unicodeReactions: Map<String, List<UnicodeReaction>> = emptyMap(),
    val replyCounts: Map<String, Int> = emptyMap(),
    val repostCounts: Map<String, Int> = emptyMap(),
    val likedReactions: Map<String, String> = emptyMap(),
    val ownEmojiReactionEventIds: Map<String, Map<String, String>> = emptyMap(),
    val loadedDates: Set<LocalDate> = emptySet(),
    val loadedKindsByDate: Map<LocalDate, Set<JournalLoadKind>> = emptyMap(),
    val isLoading: Boolean = false,
    val deleteDialog: JournalDeleteDialogState? = null,
    val noteDeleteDialog: JournalNoteDeleteDialogState? = null,
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
        memos
            .filter { isSameMonth(dateOfEpochSeconds(it.displayTime), selectedMonth) }
            .forEach { add(JournalEntry.Memo(it)) }
        notes
            .filter { isSameMonth(dateOfEpochSeconds(it.createdAt), selectedMonth) }
            .forEach { add(JournalEntry.Note(it, profiles[it.pubkey])) }
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
    private var subscriptionSequence = 0L
    private var activeLoadKinds: Set<JournalLoadKind> = defaultJournalLoadKinds()

    fun setLoadKinds(kinds: Set<JournalLoadKind>) {
        val normalized = kinds.ifEmpty { defaultJournalLoadKinds() }
        if (activeLoadKinds == normalized) return
        activeLoadKinds = normalized
        loadMonth(_state.value.selectedMonth)
    }

    fun selectDate(date: LocalDate) {
        _state.value = _state.value.copy(selectedDate = date)
        loadDate(date)
    }

    fun previousDate() {
        _state.value.previousJournalDate(activeLoadKinds, includeLikes = targetPubkey == null)
            ?.let { navigateDate(it) }
    }

    fun nextDate() {
        _state.value.nextJournalDate(activeLoadKinds, includeLikes = targetPubkey == null)
            ?.let { navigateDate(it) }
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
        val previous = _state.value.selectedMonth.previousMonth()
        loadMonth(
            previous,
            selectedDate = _state.value.lastJournalDateInMonthOrEnd(
                monthStart = previous,
                loadKinds = activeLoadKinds,
                includeLikes = targetPubkey == null,
            ),
        )
    }

    fun nextMonth() {
        val next = _state.value.selectedMonth.nextMonth()
        if (next <= currentMonth()) {
            loadMonth(
                next,
                selectedDate = _state.value.firstJournalDateInMonthOrStart(
                    monthStart = next,
                    loadKinds = activeLoadKinds,
                    includeLikes = targetPubkey == null,
                ),
            )
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
        loadDate(today, forceRefresh = true)
    }

    fun refresh() {
        if (_state.value.showCalendar) {
            loadDate(_state.value.selectedDate, forceRefresh = true)
        } else {
            loadMonth(_state.value.selectedMonth, refreshMonth = true)
        }
    }

    fun setRelayUrl(url: String?) {
        if (hasConfiguredRelayUrl && relayUrl == url) return
        hasConfiguredRelayUrl = true
        relayUrl = url
        loadMonth(_state.value.selectedMonth, resetCache = true)
    }

    private fun navigateDate(date: LocalDate) {
        if (isSameMonth(date, _state.value.selectedMonth)) {
            selectDate(date)
        } else {
            loadMonth(date.monthStart(), selectedDate = date)
        }
    }

    fun react(eventId: String, eventPubkey: String) {
        val cur = _state.value
        if (
            cur.likedReactions.containsKey(eventId) ||
            cur.ownEmojiReactionEventIds[eventId].orEmpty().isNotEmpty()
        ) return
        _state.value = cur.copy(
            likedReactions = cur.likedReactions + (eventId to ""),
            reactionCounts = cur.reactionCounts + (eventId to (cur.reactionCounts[eventId] ?: 0) + 1),
            likeReactionCounts = cur.likeReactionCounts +
                (eventId to (cur.likeReactionCounts[eventId] ?: 0) + 1),
        )
        launch {
            val privateKeyHex = KeyStorage.loadPrivateKey() ?: run {
                _state.value = _state.value.withoutOptimisticLike(eventId)
                return@launch
            }
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
            }.onFailure {
                _state.value = _state.value.withoutOptimisticLike(eventId)
            }
        }
    }

    fun unreact(eventId: String) {
        val cur = _state.value
        val reactionEventId = cur.likedReactions[eventId] ?: return
        _state.value = cur.copy(
            likedReactions = cur.likedReactions - eventId,
            reactionCounts = cur.reactionCounts + (eventId to maxOf(0, (cur.reactionCounts[eventId] ?: 0) - 1)),
            likeReactionCounts = cur.likeReactionCounts + (
                eventId to maxOf(0, (cur.likeReactionCounts[eventId] ?: 0) - 1)
                ),
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

    fun reactWithEmoji(eventId: String, eventPubkey: String, option: ReactionOption) {
        val cur = _state.value
        if (
            cur.likedReactions.containsKey(eventId) ||
            cur.ownEmojiReactionEventIds[eventId].orEmpty().isNotEmpty()
        ) return
        _state.value = cur.withOptimisticEmojiReaction(eventId, option, "")
        launch {
            val privateKeyHex = KeyStorage.loadPrivateKey() ?: run {
                _state.value = _state.value.withoutOptimisticEmojiReaction(eventId, option)
                return@launch
            }
            runCatching {
                signEvent(
                    privateKeyHex = privateKeyHex,
                    content = option.eventContent,
                    kind = 7,
                    tags = option.eventTags(eventId, eventPubkey),
                ).also { NostrRepository.publish(it) }
            }.onSuccess { reaction ->
                val state = _state.value
                _state.value = state.copy(
                    ownEmojiReactionEventIds = state.ownEmojiReactionEventIds + (
                        eventId to state.ownEmojiReactionEventIds[eventId].orEmpty()
                            .plus(option.key to reaction.id)
                        ),
                )
            }.onFailure {
                _state.value = _state.value.withoutOptimisticEmojiReaction(eventId, option)
            }
        }
    }

    fun unreactWithEmoji(eventId: String, option: ReactionOption) {
        val cur = _state.value
        val reactionEventId = cur.ownEmojiReactionEventIds[eventId]?.get(option.key) ?: return
        _state.value = cur.withoutOptimisticEmojiReaction(eventId, option)
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

    fun showNoteDeleteDialog(event: NostrEvent) {
        _state.value = _state.value.copy(
            noteDeleteDialog = JournalNoteDeleteDialogState(event = event),
        )
    }

    fun dismissNoteDeleteDialog() {
        if (_state.value.noteDeleteDialog?.isDeleting == true) return
        _state.value = _state.value.copy(noteDeleteDialog = null)
    }

    fun deleteSelectedNote() {
        val dialog = _state.value.noteDeleteDialog ?: return
        _state.value = _state.value.copy(
            noteDeleteDialog = dialog.copy(isDeleting = true, error = null),
        )
        launch {
            val privateKeyHex = KeyStorage.loadPrivateKey() ?: run {
                _state.value = _state.value.copy(
                    noteDeleteDialog = _state.value.noteDeleteDialog?.copy(
                        isDeleting = false,
                        error = "秘密鍵が設定されていません",
                    ),
                )
                return@launch
            }
            val signerPubkey = derivePublicKey(privateKeyHex.fromHex()).toHex()
            if (dialog.event.pubkey != signerPubkey) {
                _state.value = _state.value.copy(
                    noteDeleteDialog = _state.value.noteDeleteDialog?.copy(
                        isDeleting = false,
                        error = "自分の投稿だけ削除できます",
                    ),
                )
                return@launch
            }

            runCatching {
                val deletion = signEvent(
                    privateKeyHex = privateKeyHex,
                    content = "",
                    kind = 5,
                    tags = listOf(
                        listOf("e", dialog.event.id),
                        listOf("k", dialog.event.kind.toString()),
                        listOf("client", "ToriNos"),
                    ),
                )
                NostrRepository.publish(deletion)
            }.onSuccess {
                _state.value = _state.value.copy(
                    notes = _state.value.notes.filterNot { it.id == dialog.event.id },
                    noteDeleteDialog = null,
                )
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    noteDeleteDialog = _state.value.noteDeleteDialog?.copy(
                        isDeleting = false,
                        error = e.message ?: "投稿の削除要求を送信できませんでした",
                    ),
                )
            }
        }
    }

    private fun loadMonth(
        month: LocalDate,
        selectedDate: LocalDate? = null,
        refreshMonth: Boolean = false,
        resetCache: Boolean = false,
    ) {
        referencedContentJob?.cancel()
        engagementJob?.cancel()
        monthBackfillJob?.cancel()
        loadJob?.cancel()

        val monthStart = month.monthStart()
        val nextSelectedDate = selectedDate
            ?.takeIf { it.year == monthStart.year && it.month == monthStart.month }
            ?: _state.value.selectedDate
            .takeIf { it.year == monthStart.year && it.month == monthStart.month }
            ?: monthStart
        val currentState = _state.value
        val retainedMemos = when {
            resetCache -> emptyList()
            refreshMonth -> currentState.memos.filterNot { isSameMonth(dateOfEpochSeconds(it.displayTime), monthStart) }
            else -> currentState.memos
        }
        val retainedNotes = when {
            resetCache -> emptyList()
            refreshMonth -> currentState.notes.filterNot { isSameMonth(dateOfEpochSeconds(it.createdAt), monthStart) }
            else -> currentState.notes
        }
        val retainedLoadedDates = when {
            resetCache -> emptySet()
            refreshMonth -> currentState.loadedDates.filterNot { isSameMonth(it, monthStart) }.toSet()
            else -> currentState.loadedDates
        }
        val retainedLoadedKindsByDate = when {
            resetCache -> emptyMap()
            refreshMonth -> currentState.loadedKindsByDate.filterKeys { !isSameMonth(it, monthStart) }
            else -> currentState.loadedKindsByDate
        }
        _state.value = currentState.copy(
            selectedMonth = monthStart,
            selectedDate = nextSelectedDate,
            isLoading = true,
            memos = retainedMemos,
            notes = retainedNotes,
            quotedEvents = if (resetCache) emptyMap() else currentState.quotedEvents,
            reactionCounts = if (resetCache) emptyMap() else currentState.reactionCounts,
            likeReactionCounts = if (resetCache) emptyMap() else currentState.likeReactionCounts,
            customReactions = if (resetCache) emptyMap() else currentState.customReactions,
            unicodeReactions = if (resetCache) emptyMap() else currentState.unicodeReactions,
            replyCounts = if (resetCache) emptyMap() else currentState.replyCounts,
            repostCounts = if (resetCache) emptyMap() else currentState.repostCounts,
            likedReactions = if (resetCache) emptyMap() else currentState.likedReactions,
            ownEmojiReactionEventIds = if (resetCache) {
                emptyMap()
            } else {
                currentState.ownEmojiReactionEventIds
            },
            loadedDates = retainedLoadedDates,
            loadedKindsByDate = retainedLoadedKindsByDate,
            error = null,
        )

        loadJob = launch {
            try {
                val context = resolveLoadContext() ?: return@launch

                val profile = if (_state.value.profiles.containsKey(context.publicKeyHex)) {
                    _state.value.profiles[context.publicKeyHex]
                } else {
                    fetchProfile(context.publicKeyHex, relayUrl)
                }
                val loadKinds = activeLoadKinds
                val missingKinds = missingLoadKinds(nextSelectedDate, loadKinds)
                val (memoEvents, noteEvents) = if (missingKinds.isEmpty()) {
                    emptyList<NostrEvent>() to emptyList()
                } else {
                    fetchEvents(
                        pubkey = context.publicKeyHex,
                        since = nextSelectedDate.startOfDayEpochSeconds(),
                        until = nextSelectedDate.plusDays(1).startOfDayEpochSeconds() - 1,
                        relayUrl = relayUrl,
                        loadKinds = missingKinds,
                        limit = JOURNAL_DATE_LIMIT,
                    )
                }
                val memos = decodeMemoEvents(memoEvents, context)
                val notes = noteEvents.sortedByDescending { it.createdAt }

                val profiles = if (profile != null) {
                    _state.value.profiles + (context.publicKeyHex to profile)
                } else {
                    _state.value.profiles
                }

                _state.value = _state.value.copy(
                    isLoading = false,
                    memos = mergeJournalMemos(_state.value.memos, memos),
                    notes = mergeNotes(_state.value.notes, notes),
                    profiles = profiles,
                    loadedDates = _state.value.loadedDates + nextSelectedDate,
                    loadedKindsByDate = markLoadedKinds(nextSelectedDate, missingKinds),
                    error = null,
                )

                val noteIds = notes.map { it.id }
                if (noteIds.isNotEmpty()) {
                    fetchEngagement(noteIds, ownPublicKeyHex)
                }
                fetchReferencedContentNow(notes, memos, relayUrl)
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
                val lastDay = minOf(monthStart.nextMonth().minusDays(1), currentDate())
                val dates = generateSequence(monthStart) { it.plusDays(1) }
                    .takeWhile { it <= lastDay }
                    .filterNot { it == loadedDate }

                for (date in dates) {
                    if (_state.value.selectedMonth != monthStart) return@launch
                    val missingKinds = missingLoadKinds(date, activeLoadKinds)
                    if (missingKinds.isEmpty()) continue
                    val (memoEvents, noteEvents) = fetchEvents(
                        pubkey = context.publicKeyHex,
                        since = date.startOfDayEpochSeconds(),
                        until = date.plusDays(1).startOfDayEpochSeconds() - 1,
                        relayUrl = relayUrl,
                        loadKinds = missingKinds,
                        limit = JOURNAL_MONTH_DAY_LIMIT,
                    )
                    if (_state.value.selectedMonth != monthStart) return@launch

                    val memos = decodeMemoEvents(memoEvents, context)
                    _state.value = _state.value.copy(
                        memos = mergeJournalMemos(_state.value.memos, memos),
                        notes = (_state.value.notes + noteEvents)
                            .distinctBy { it.id }
                            .sortedByDescending { it.createdAt },
                        loadedDates = _state.value.loadedDates + date,
                        loadedKindsByDate = markLoadedKinds(date, missingKinds),
                    )
                    fetchReferencedContentNow(noteEvents, memos, relayUrl)
                }
            } catch (e: CancellationException) {
                throw e
            }
        }
    }

    private fun loadDate(date: LocalDate, forceRefresh: Boolean = false) {
        referencedContentJob?.cancel()
        engagementJob?.cancel()
        loadJob?.cancel()
        val loadKinds = activeLoadKinds
        val missingKinds = if (forceRefresh) loadKinds else missingLoadKinds(date, loadKinds)
        if (missingKinds.isEmpty()) {
            val notes = notesForDate(date)
            val memos = memosForDate(date)
            _state.value = _state.value.copy(
                selectedMonth = date.monthStart(),
                selectedDate = date,
                isLoading = false,
                error = null,
            )
            val noteIds = notes.map { it.id }
            if (noteIds.isNotEmpty()) {
                fetchEngagement(noteIds, ownPublicKeyHex)
            }
            fetchReferencedContent(notes, memos, relayUrl)
            return
        }
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
                    loadKinds = missingKinds,
                    limit = JOURNAL_DATE_LIMIT,
                )
                val memos = decodeMemoEvents(memoEvents, context)
                val notes = noteEvents.sortedByDescending { it.createdAt }
                _state.value = _state.value.copy(
                    isLoading = false,
                    memos = mergeJournalMemos(_state.value.memos, memos),
                    notes = mergeNotes(_state.value.notes, notes),
                    loadedDates = _state.value.loadedDates + date,
                    loadedKindsByDate = markLoadedKinds(date, missingKinds),
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

    private suspend fun fetchEvents(
        pubkey: String,
        since: Long,
        until: Long,
        relayUrl: String? = null,
        loadKinds: Set<JournalLoadKind> = defaultJournalLoadKinds(),
        limit: Int = JOURNAL_DATE_LIMIT,
    ): Pair<List<NostrEvent>, List<NostrEvent>> = coroutineScope {
        val includeMemos = targetPubkey == null && JournalLoadKind.Memo in loadKinds
        val noteKinds = noteKindsForLoadKinds(loadKinds, targetPubkey == null)
        val subId = nextSubscriptionId("journal")
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
                        event.pubkey == pubkey && event.matchesLoadKinds(loadKinds, targetPubkey == null) ->
                            mutex.withLock { noteEvents += event }
                    }
                }
            }
            val kinds = if (includeMemos) listOf(MEMO_EVENT_KIND) + noteKinds else noteKinds
            if (kinds.isEmpty()) return@coroutineScope Pair(emptyList(), emptyList())
            NostrRepository.subscribe(
                subId,
                NostrFilter(
                    kinds = kinds,
                    authors = listOf(pubkey),
                    since = since,
                    until = until,
                    limit = limit,
                ),
                relayUrl = relayUrl,
            )
            awaitSubscriptionEnd(subId, MEMO_FETCH_TIMEOUT_MS)
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
        val subId = nextSubscriptionId("memo-prof")
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
            awaitSubscriptionEnd(subId, 5_000L)
            result
        } finally {
            runCatching { NostrRepository.close(subId) }
            collector?.cancelAndJoin()
        }
    }

    private fun fetchEngagement(noteIds: List<String>, ownPubkey: String?) {
        engagementJob?.cancel()
        engagementJob = launch {
            val subId = nextSubscriptionId("memo-engage")
            val quoteRepostSubId = nextSubscriptionId("memo-qrepost")
            val noteIdSet = noteIds.toHashSet()
            val mutex = Mutex()
            val reactionCounts = mutableMapOf<String, Int>()
            val likeReactionCounts = mutableMapOf<String, Int>()
            val customReactions = mutableMapOf<String, List<CustomReaction>>()
            val unicodeReactions = mutableMapOf<String, List<UnicodeReaction>>()
            val replyCounts = mutableMapOf<String, Int>()
            val repostCounts = mutableMapOf<String, Int>()
            val likedReactions = mutableMapOf<String, String>()
            val ownEmojiReactionEventIds = mutableMapOf<String, Map<String, String>>()
            var collector: Job? = null
            var quoteRepostCollector: Job? = null
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
                                    if (event.content.trim() == "+") {
                                        likeReactionCounts[targetId] =
                                            (likeReactionCounts[targetId] ?: 0) + 1
                                    }
                                    event.toCustomReaction()?.let { reaction ->
                                        customReactions[targetId] = customReactions[targetId]
                                            .orEmpty()
                                            .incrementedWith(reaction)
                                    }
                                    event.toUnicodeReaction()?.let { reaction ->
                                        unicodeReactions[targetId] = unicodeReactions[targetId]
                                            .orEmpty()
                                            .incrementedWithUnicodeReaction(reaction)
                                    }
                                    if (
                                        event.pubkey == ownPubkey &&
                                        event.content.trim() == "+" &&
                                        !likedReactions.containsKey(targetId)
                                    ) {
                                        likedReactions[targetId] = event.id
                                    }
                                    if (event.pubkey == ownPubkey) {
                                        event.toReactionOption()?.let { option ->
                                            ownEmojiReactionEventIds[targetId] =
                                                ownEmojiReactionEventIds[targetId].orEmpty() +
                                                    (option.key to event.id)
                                        }
                                    }
                                }
                                1 -> replyCounts[targetId] = (replyCounts[targetId] ?: 0) + 1
                                6 -> repostCounts[targetId] = (repostCounts[targetId] ?: 0) + 1
                            }
                        }
                    }
                }
                quoteRepostCollector = launch {
                    NostrRepository.events(quoteRepostSubId).collect { event ->
                        if (event.kind != 1) return@collect
                        val targetIds = event.tags
                            .filter { it.firstOrNull() == "q" }
                            .mapNotNull { it.getOrNull(1) }
                            .distinct()
                            .filter { it in noteIdSet }
                        mutex.withLock {
                            targetIds.forEach { targetId ->
                                repostCounts[targetId] = (repostCounts[targetId] ?: 0) + 1
                            }
                        }
                    }
                }
                val completionWaiters = listOf(
                    async { awaitSubscriptionEnd(subId, 8_000L) },
                    async { awaitSubscriptionEnd(quoteRepostSubId, 8_000L) },
                )
                NostrRepository.subscribe(
                    subId,
                    NostrFilter(kinds = listOf(1, 6, 7), eTags = noteIds, limit = 500),
                    relayUrl = relayUrl,
                )
                NostrRepository.subscribe(
                    quoteRepostSubId,
                    NostrFilter(kinds = listOf(1), qTags = noteIds, limit = 500),
                    relayUrl = relayUrl,
                )
                completionWaiters.awaitAll()
                mutex.withLock {
                    val retainedReactionCounts = _state.value.reactionCounts - noteIdSet
                    val retainedLikeReactionCounts = _state.value.likeReactionCounts - noteIdSet
                    val retainedCustomReactions = _state.value.customReactions - noteIdSet
                    val retainedUnicodeReactions = _state.value.unicodeReactions - noteIdSet
                    val retainedReplyCounts = _state.value.replyCounts - noteIdSet
                    val retainedRepostCounts = _state.value.repostCounts - noteIdSet
                    val retainedLikedReactions = _state.value.likedReactions - noteIdSet
                    val retainedOwnEmojiReactions =
                        _state.value.ownEmojiReactionEventIds - noteIdSet
                    _state.value = _state.value.copy(
                        reactionCounts = retainedReactionCounts + reactionCounts,
                        likeReactionCounts = retainedLikeReactionCounts + likeReactionCounts,
                        customReactions = retainedCustomReactions + customReactions,
                        unicodeReactions = retainedUnicodeReactions + unicodeReactions,
                        replyCounts = retainedReplyCounts + replyCounts,
                        repostCounts = retainedRepostCounts + repostCounts,
                        likedReactions = retainedLikedReactions + likedReactions,
                        ownEmojiReactionEventIds =
                            retainedOwnEmojiReactions + ownEmojiReactionEventIds,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                runCatching { NostrRepository.close(subId) }
                runCatching { NostrRepository.close(quoteRepostSubId) }
                collector?.cancelAndJoin()
                quoteRepostCollector?.cancelAndJoin()
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

    private fun mergeNotes(
        current: List<NostrEvent>,
        additions: List<NostrEvent>,
    ): List<NostrEvent> =
        (current + additions)
            .distinctBy { it.id }
            .sortedByDescending { it.createdAt }

    private fun memosForDate(date: LocalDate): List<JournalItem> =
        _state.value.memos.filter { dateOfEpochSeconds(it.displayTime) == date }

    private fun notesForDate(date: LocalDate): List<NostrEvent> =
        _state.value.notes.filter { dateOfEpochSeconds(it.createdAt) == date }

    private fun missingLoadKinds(date: LocalDate, requestedKinds: Set<JournalLoadKind>): Set<JournalLoadKind> =
        requestedKinds - _state.value.loadedKindsByDate[date].orEmpty()

    private fun markLoadedKinds(date: LocalDate, loadedKinds: Set<JournalLoadKind>): Map<LocalDate, Set<JournalLoadKind>> =
        if (loadedKinds.isEmpty()) {
            _state.value.loadedKindsByDate
        } else {
            _state.value.loadedKindsByDate + (date to (_state.value.loadedKindsByDate[date].orEmpty() + loadedKinds))
        }

    private fun nextSubscriptionId(prefix: String): String {
        subscriptionSequence += 1
        return "$prefix-${Clock.System.now().toEpochMilliseconds()}-${subscriptionSequence}-${Random.nextInt()}"
    }

    private suspend fun awaitSubscriptionEnd(subId: String, timeoutMillis: Long) {
        withTimeoutOrNull(timeoutMillis) {
            merge(
                NostrRepository.endOfStoredEvents(subId),
                NostrRepository.closedMessages(subId).map { closed ->
                    appLog("[Journal] CLOSED subId=$subId reason=${closed.message}")
                    Unit
                },
            ).first()
        }
    }

    private fun fetchReferencedContent(notes: List<NostrEvent>, memos: List<JournalItem>, relayUrl: String?) {
        referencedContentJob?.cancel()
        referencedContentJob = launch {
            try {
                fetchReferencedContentNow(notes, memos, relayUrl)
            } catch (e: CancellationException) {
                throw e
            }
        }
    }

    private suspend fun fetchReferencedContentNow(notes: List<NostrEvent>, memos: List<JournalItem>, relayUrl: String?) = coroutineScope {
        val eventIdsToFetch = buildSet {
            notes.forEach { event ->
                event.replyTargetId()?.let { add(it) }
                addAll(quotedEventIds(event))
                event.activityTargetId()?.let { add(it) }
            }
        }.filterNot { _state.value.quotedEvents.containsKey(it) }
            .toSet()
        val memoPubkeys = memos.mapNotNull { it.memo.replyToPubkey }.toHashSet()

        val fetchedEvents = mutableListOf<NostrEvent>()

        if (eventIdsToFetch.isNotEmpty()) {
            val subId = nextSubscriptionId("memo-refs")
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
                    NostrFilter(ids = eventIdsToFetch.toList(), limit = eventIdsToFetch.size),
                    relayUrl = relayUrl,
                )
                awaitSubscriptionEnd(subId, 5_000L)
                _state.value = _state.value.copy(
                    quotedEvents = _state.value.quotedEvents + fetchedEvents.associateBy { it.id },
                )
            } finally {
                runCatching { NostrRepository.close(subId) }
                collector?.cancelAndJoin()
            }
        }

        val referencedEvents = fetchedEvents + eventIdsToFetch.mapNotNull { _state.value.quotedEvents[it] }
        val pubkeysToFetch = buildSet {
            addAll(memoPubkeys)
            referencedEvents.forEach { add(it.pubkey) }
        }.filterNot { _state.value.profiles.containsKey(it) }

        if (pubkeysToFetch.isNotEmpty()) {
            val subId = nextSubscriptionId("memo-ref-prof")
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
                awaitSubscriptionEnd(subId, 5_000L)
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

internal fun mergeJournalMemos(
    current: List<JournalItem>,
    additions: List<JournalItem>,
): List<JournalItem> {
    val latestByAddress = linkedMapOf<String, JournalItem>()
    (current + additions).forEach { item ->
        val address = item.memo.identifier
            ?.let { identifier -> "${item.pubkey}:$identifier" }
            ?: "event:${item.eventId}"
        val existing = latestByAddress[address]
        val shouldReplace = existing == null ||
            item.createdAt > existing.createdAt ||
            (item.createdAt == existing.createdAt && item.eventId < existing.eventId)
        if (shouldReplace) {
            latestByAddress[address] = item
        }
    }
    return latestByAddress.values.sortedByDescending { it.displayTime }
}

private fun NostrEvent.activityTargetId(): String? =
    tags.lastOrNull { it.firstOrNull() == "e" }?.getOrNull(1)
        ?: embeddedRepostTarget()?.id

private fun NostrEvent.matchesLoadKinds(loadKinds: Set<JournalLoadKind>, includeLikes: Boolean): Boolean =
    when (kind) {
        1 -> if (replyTargetId() != null) JournalLoadKind.Reply in loadKinds else JournalLoadKind.Post in loadKinds
        6 -> JournalLoadKind.Repost in loadKinds
        7 -> includeLikes && JournalLoadKind.Like in loadKinds
        NIP23_ARTICLE_KIND -> JournalLoadKind.Article in loadKinds
        else -> false
    }

private fun NostrEvent.embeddedRepostTarget(): NostrEvent? {
    if (kind != 6 || content.isBlank()) return null
    return runCatching {
        Json.decodeFromString(NostrEvent.serializer(), content)
    }.getOrNull()
}

private fun noteKindsForLoadKinds(loadKinds: Set<JournalLoadKind>, includeLikes: Boolean): List<Int> =
    buildList {
        if (JournalLoadKind.Post in loadKinds || JournalLoadKind.Reply in loadKinds) add(1)
        if (JournalLoadKind.Repost in loadKinds) add(6)
        if (includeLikes && JournalLoadKind.Like in loadKinds) add(7)
        if (JournalLoadKind.Article in loadKinds) add(NIP23_ARTICLE_KIND)
    }

private fun defaultJournalLoadKinds(): Set<JournalLoadKind> =
    setOf(JournalLoadKind.Post)

private fun JournalItem.addressTagValue(): String? {
    val d = memo.identifier
        ?: return null
    return "$MEMO_EVENT_KIND:$pubkey:$d"
}

private fun isSameMonth(date: LocalDate, monthStart: LocalDate): Boolean =
    date.year == monthStart.year && date.month == monthStart.month

private fun JournalState.previousJournalDate(
    loadKinds: Set<JournalLoadKind>,
    includeLikes: Boolean,
): LocalDate? {
    val currentMonthStart = selectedDate.monthStart()
    journalEntryDatesInMonth(currentMonthStart, loadKinds, includeLikes)
        .filter { it < selectedDate }
        .maxOrNull()
        ?.let { return it }

    if (selectedDate > currentMonthStart) return currentMonthStart

    val previousMonthStart = currentMonthStart.previousMonth()
    val previousMonthEnd = monthEnd(previousMonthStart)
    return journalEntryDatesInMonth(previousMonthStart, loadKinds, includeLikes)
        .filter { it <= previousMonthEnd }
        .maxOrNull()
        ?: previousMonthEnd
}

private fun JournalState.nextJournalDate(
    loadKinds: Set<JournalLoadKind>,
    includeLikes: Boolean,
): LocalDate? {
    val today = currentDate()
    val currentMonthStart = selectedDate.monthStart()
    val currentMonthEnd = minOf(monthEnd(currentMonthStart), today)
    journalEntryDatesInMonth(currentMonthStart, loadKinds, includeLikes)
        .filter { it > selectedDate && it <= today }
        .minOrNull()
        ?.let { return it }

    if (selectedDate < currentMonthEnd) return currentMonthEnd
    if (currentMonthEnd == today) return null

    val nextMonthStart = currentMonthStart.nextMonth()
    val nextMonthEnd = minOf(monthEnd(nextMonthStart), today)
    return journalEntryDatesInMonth(nextMonthStart, loadKinds, includeLikes)
        .filter { it <= nextMonthEnd }
        .minOrNull()
        ?: nextMonthStart
}

private fun JournalState.firstJournalDateInMonthOrStart(
    monthStart: LocalDate,
    loadKinds: Set<JournalLoadKind>,
    includeLikes: Boolean,
): LocalDate =
    journalEntryDatesInMonth(monthStart, loadKinds, includeLikes)
        .filter { it <= minOf(monthEnd(monthStart), currentDate()) }
        .minOrNull()
        ?: monthStart

private fun JournalState.lastJournalDateInMonthOrEnd(
    monthStart: LocalDate,
    loadKinds: Set<JournalLoadKind>,
    includeLikes: Boolean,
): LocalDate {
    val end = minOf(monthEnd(monthStart), currentDate())
    return journalEntryDatesInMonth(monthStart, loadKinds, includeLikes)
        .filter { it <= end }
        .maxOrNull()
        ?: end
}

private fun JournalState.journalEntryDatesInMonth(
    monthStart: LocalDate,
    loadKinds: Set<JournalLoadKind>,
    includeLikes: Boolean,
): List<LocalDate> =
    buildSet {
        if (JournalLoadKind.Memo in loadKinds) {
            memos
                .map { dateOfEpochSeconds(it.displayTime) }
                .filterTo(this) { isSameMonth(it, monthStart) }
        }
        notes
            .filter { it.matchesLoadKinds(loadKinds, includeLikes) }
            .map { dateOfEpochSeconds(it.createdAt) }
            .filterTo(this) { isSameMonth(it, monthStart) }
    }
        .sorted()

private fun monthEnd(monthStart: LocalDate): LocalDate =
    monthStart.nextMonth().minusDays(1)

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
