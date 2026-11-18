package com.nostr.torinos.ui.chat

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatViewToggleStateTest {
    private val relay = ChatSelection.Relay("wss://buzz.example")

    @Test
    fun toggleIsShownOnlyForASelectedRelay() {
        assertTrue(ChatTabUiState(selection = relay).showsViewToggle)
        assertFalse(ChatTabUiState(selection = ChatSelection.All).showsViewToggle)
        assertFalse(ChatTabUiState(selection = ChatSelection.Direct).showsViewToggle)
        assertFalse(ChatTabUiState(selection = relay, hasSigner = false).showsViewToggle)
    }

    @Test
    fun savedTimelineViewFallsBackToGroupsOnAll() {
        assertFalse(ChatTabUiState(selection = ChatSelection.All, view = ChatListView.Timeline).showsTimeline)
        assertTrue(ChatTabUiState(selection = relay, view = ChatListView.Timeline).showsTimeline)
    }
}
