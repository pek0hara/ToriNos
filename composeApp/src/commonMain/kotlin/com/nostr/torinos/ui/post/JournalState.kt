package com.nostr.torinos.ui.post

import com.nostr.torinos.journal.JournalActivity
import com.nostr.torinos.journal.JournalActivityKind
import com.nostr.torinos.journal.JournalClock
import com.nostr.torinos.journal.JournalCoverage
import com.nostr.torinos.journal.JournalNoteEngagement
import com.nostr.torinos.journal.JournalTimeline
import com.nostr.torinos.journal.defaultJournalKinds
import com.nostr.torinos.journal.monthStart
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrProfile
import kotlinx.datetime.LocalDate

data class JournalNoteDeleteDialogState(
    val event: NostrEvent,
    val isDeleting: Boolean = false,
    val error: String? = null,
)

/**
 * ジャーナル画面の状態。
 * [visibleEntries]と[entryCountsByDate]は導出値だが、Composeが読むたびに計算しないよう保持する。
 * 更新は[withDerived]を通し、投稿本体・種類・選択日・表示モードが変わったときだけ作り直す。
 */
data class JournalState(
    val isSelf: Boolean,
    val today: LocalDate,
    val selectedMonth: LocalDate,
    val selectedDate: LocalDate,
    val kinds: Set<JournalActivityKind> = defaultJournalKinds(),
    val showCalendar: Boolean = true,
    val timeline: JournalTimeline = JournalTimeline.Empty,
    val coverage: JournalCoverage = JournalCoverage(),
    val referencedEvents: Map<String, NostrEvent> = emptyMap(),
    val profiles: Map<String, NostrProfile> = emptyMap(),
    val engagement: Map<String, JournalNoteEngagement> = emptyMap(),
    val visibleEntries: List<JournalActivity> = emptyList(),
    val entryCountsByDate: Map<LocalDate, Int> = emptyMap(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val engagementError: String? = null,
    val noteDeleteDialog: JournalNoteDeleteDialogState? = null,
) {
    val canGoNextMonth: Boolean get() = selectedMonth < today.monthStart()
    val canGoNextDate: Boolean get() = selectedDate < today

    fun engagementOf(eventId: String): JournalNoteEngagement = engagement[eventId] ?: EmptyEngagement

    companion object {
        internal fun initial(isSelf: Boolean, clock: JournalClock = JournalClock()): JournalState {
            val today = clock.today()
            return JournalState(
                isSelf = isSelf,
                today = today,
                selectedMonth = today.monthStart(),
                selectedDate = today,
            )
        }

        private val EmptyEngagement = JournalNoteEngagement()
    }
}

/** 導出値を作り直す。中身が変わらなければ前のインスタンスを使い、一覧と可視範囲の監視を作り直させない。 */
internal fun JournalState.withDerived(): JournalState {
    val entries = if (showCalendar) {
        timeline.entries(selectedDate, kinds)
    } else {
        timeline.monthEntries(selectedMonth, kinds)
    }
    val counts = timeline.countsByDate(selectedMonth, kinds)
    return copy(
        visibleEntries = if (entries == visibleEntries) visibleEntries else entries,
        entryCountsByDate = if (counts == entryCountsByDate) entryCountsByDate else counts,
    )
}

/** [previous]から[next]への更新で導出値を作り直す必要があるか。 */
internal fun needsDerivedUpdate(previous: JournalState, next: JournalState): Boolean =
    previous.timeline !== next.timeline ||
        previous.kinds != next.kinds ||
        previous.selectedDate != next.selectedDate ||
        previous.selectedMonth != next.selectedMonth ||
        previous.showCalendar != next.showCalendar
