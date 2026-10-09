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
 * [visibleEntries]と[entryCountsByDate]と[kindCounts]は導出値だが、Composeが読むたびに計算しないよう保持する。
 * 更新は[withDerived]を通し、投稿本体・取得範囲・種類・選択日・表示モードが変わったときだけ作り直す。
 */
/** メンバー候補。[interactionCount]は直近のいいね・返信・リポストのやりとりの件数。 */
data class JournalMemberCandidate(val pubkey: String, val interactionCount: Int)

/** [fetchedAtMillis]は最後に取得し終えた時刻。しばらくはシートを開き直しても取り直さない。 */
data class JournalMemberCandidates(
    val isLoading: Boolean = false,
    val candidates: List<JournalMemberCandidate> = emptyList(),
    val fetchedAtMillis: Long? = null,
)

data class JournalState(
    val isSelf: Boolean,
    /** 並び順どおりのメンバー。自分のジャーナルでは先頭が自分。 */
    val members: List<String> = emptyList(),
    /** 明示的に選んだメンバー。空なら先頭のメンバー（自分、または開いた人）だけを表示する。 */
    val selectedMembers: Set<String> = emptySet(),
    val memberCandidates: JournalMemberCandidates = JournalMemberCandidates(),
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
    /** 表示中の日（月一覧では月）の種類ごとの件数。取得し終えていない種類は含めない。 */
    val kindCounts: Map<JournalActivityKind, Int> = emptyMap(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val engagementError: String? = null,
    val noteDeleteDialog: JournalNoteDeleteDialogState? = null,
) {
    val canGoNextMonth: Boolean get() = selectedMonth < today.monthStart()
    val canGoNextDate: Boolean get() = selectedDate < today
    val isShowingToday: Boolean
        get() = if (showCalendar) selectedDate == today else selectedMonth == today.monthStart()

    /** 取得する（表示中の）メンバー。選んでいないメンバーの分は、選んだときに取る。 */
    val fetchMembers: Set<String>
        get() = selectedMembers.ifEmpty { setOfNotNull(members.firstOrNull()) }

    /** 表示するメンバー。全員を表示するなら null。 */
    val visibleMembers: Set<String>?
        get() = fetchMembers.takeIf { it != members.toSet() }

    /** 先頭（自分、または開いた人）のほかに追加できる余地があるか。 */
    val canAddMember: Boolean get() = members.size - 1 < JOURNAL_MAX_ADDED_MEMBERS

    fun engagementOf(eventId: String): JournalNoteEngagement = engagement[eventId] ?: EmptyEngagement

    companion object {
        internal fun initial(
            isSelf: Boolean,
            members: List<String>,
            clock: JournalClock = JournalClock(),
            showCalendar: Boolean = false,
        ): JournalState {
            val today = clock.today()
            return JournalState(
                isSelf = isSelf,
                members = members,
                showCalendar = showCalendar,
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
    val members = visibleMembers
    val entries = if (showCalendar) {
        timeline.entries(selectedDate, kinds, members)
    } else {
        timeline.monthEntries(selectedMonth, kinds, members)
    }
    val counts = timeline.countsByDate(selectedMonth, kinds, members)
    val byKind = loadedKindCounts()
    return copy(
        visibleEntries = if (entries == visibleEntries) visibleEntries else entries,
        entryCountsByDate = if (counts == entryCountsByDate) entryCountsByDate else counts,
        kindCounts = if (byKind == kindCounts) kindCounts else byKind,
    )
}

/** 未取得の種類を0件と見せないよう、取得し終えた種類だけを数える。 */
private fun JournalState.loadedKindCounts(): Map<JournalActivityKind, Int> {
    val allKinds = JournalActivityKind.entries.toSet()
    val members = visibleMembers
    val loadedKinds: Set<JournalActivityKind>
    val activities: List<JournalActivity>
    if (showCalendar) {
        loadedKinds = coverage.loadedKinds(selectedDate, allKinds, fetchMembers)
        activities = timeline.entries(selectedDate, loadedKinds, members)
    } else {
        loadedKinds = allKinds.filterTo(mutableSetOf()) {
            coverage.hasLoadedMonth(selectedMonth, setOf(it), today, fetchMembers)
        }
        activities = timeline.monthEntries(selectedMonth, loadedKinds, members)
    }
    if (loadedKinds.isEmpty()) return emptyMap()
    val activityKinds = activities.map { it.kindsFor(members) }
    return loadedKinds.associateWith { kind -> activityKinds.count { kind in it } }
}

/**
 * カレンダーなしで今月を見ているとき、最初に見せる行。
 * 今日の先頭の行、今日の記録がなければ一番新しい日の先頭の行。それ以外の月や日表示では null。
 */
internal fun JournalState.todayScrollIndex(): Int? {
    if (showCalendar || selectedMonth != today.monthStart()) return null
    val latest = visibleEntries.lastOrNull { it.date <= today }?.date ?: return null
    return visibleEntries.indexOfFirst { it.date == latest }
}

/**
 * 日表示から月表示へ切り替えたとき、最初に見せる行。
 * 日表示で先頭に見えていた[eventId]の行、なければ[date]以降で最初の行、それもなければ最後の行。
 */
internal fun JournalState.dayAnchorIndex(eventId: String?, date: LocalDate): Int? {
    if (visibleEntries.isEmpty()) return null
    eventId?.let { id -> visibleEntries.indexOfFirst { it.event.id == id }.takeIf { it >= 0 }?.let { return it } }
    return visibleEntries.indexOfFirst { it.date >= date }.takeIf { it >= 0 } ?: visibleEntries.lastIndex
}

/** [previous]から[next]への更新で導出値を作り直す必要があるか。 */
internal fun needsDerivedUpdate(previous: JournalState, next: JournalState): Boolean =
    previous.timeline !== next.timeline ||
        previous.coverage != next.coverage ||
        previous.kinds != next.kinds ||
        previous.members != next.members ||
        previous.selectedMembers != next.selectedMembers ||
        previous.selectedDate != next.selectedDate ||
        previous.selectedMonth != next.selectedMonth ||
        previous.showCalendar != next.showCalendar

/** 先頭のメンバーのほかに追加できる人数。 */
const val JOURNAL_MAX_ADDED_MEMBERS = 5
