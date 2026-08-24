package com.nostr.torinos.ui.post

import androidx.lifecycle.viewModelScope
import com.nostr.torinos.account.AccountSession
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.ReactionOption
import com.nostr.torinos.ui.SafeViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.LocalDate

/** Journal画面のFacade。取得・復号・分類・月索引・削除はJournalControllerが所有する。 */
class JournalViewModel(
    targetPubkey: String? = null,
    accountSession: AccountSession? = null,
) : SafeViewModel() {
    private val controller = JournalController(targetPubkey, accountSession, viewModelScope)
    val state: StateFlow<JournalState> = controller.state

    fun consumeEngagementError() = controller.consumeEngagementError()
    fun setLoadKinds(kinds: Set<JournalLoadKind>) = controller.setLoadKinds(kinds)
    fun selectDate(date: LocalDate) = controller.selectDate(date)
    fun previousDate() = controller.previousDate()
    fun nextDate() = controller.nextDate()
    fun toggleCalendar() = controller.toggleCalendar()
    fun showCalendar() = controller.showCalendar()
    fun previousMonth() = controller.previousMonth()
    fun nextMonth() = controller.nextMonth()
    fun selectMonth(year: Int, month: Int) = controller.selectMonth(year, month)
    fun refreshToday() = controller.refreshToday()
    fun refresh() = controller.refresh()
    fun setRelayUrl(url: String?) = controller.setRelayUrl(url)
    fun react(eventId: String, eventPubkey: String) = controller.react(eventId, eventPubkey)
    fun unreact(eventId: String) = controller.unreact(eventId)
    fun reactWithEmoji(eventId: String, eventPubkey: String, option: ReactionOption) =
        controller.reactWithEmoji(eventId, eventPubkey, option)
    fun unreactWithEmoji(eventId: String, option: ReactionOption) =
        controller.unreactWithEmoji(eventId, option)
    fun showDeleteDialog(item: JournalItem) = controller.showDeleteDialog(item)
    fun dismissDeleteDialog() = controller.dismissDeleteDialog()
    fun deleteSelectedMemo() = controller.deleteSelectedMemo()
    fun showNoteDeleteDialog(event: NostrEvent) = controller.showNoteDeleteDialog(event)
    fun dismissNoteDeleteDialog() = controller.dismissNoteDeleteDialog()
    fun deleteSelectedNote() = controller.deleteSelectedNote()

    override fun onCleared() {
        controller.close()
        super.onCleared()
    }
}
