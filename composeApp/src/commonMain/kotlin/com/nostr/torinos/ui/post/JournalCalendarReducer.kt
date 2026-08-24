package com.nostr.torinos.ui.post

import kotlinx.datetime.LocalDate

internal sealed interface JournalCalendarAction {
    data class SelectDate(val date: LocalDate) : JournalCalendarAction
    data class SetCalendarVisibility(val visible: Boolean) : JournalCalendarAction
}

/** 日付選択と表示モードをRepositoryやClockから独立させる純粋Reducer。 */
internal object JournalCalendarReducer {
    fun reduce(state: JournalState, action: JournalCalendarAction): JournalState = when (action) {
        is JournalCalendarAction.SelectDate -> state.copy(selectedDate = action.date)
        is JournalCalendarAction.SetCalendarVisibility -> state.copy(showCalendar = action.visible)
    }
}
