package com.nostr.torinos.ui.components

import kotlin.test.*

class NoteCardModelTest {
    @Test fun unavailableActionsStayUnavailableAndReferenceDefaultsFollowNoteAction() {
        val open: (String) -> Unit = {}
        val actions = NoteCardActions(onNoteClick = open)
        assertNull(actions.onLike)
        assertNull(actions.onDelete)
        assertSame(open, actions.onQuotedNoteClick)
        assertSame(open, actions.onReplyParentClick)
    }

    @Test fun reactionVisibilityCanBeIndependentFromEngagement() {
        assertFalse(NoteCardPolicy(showEngagement = false).showReactions)
        assertTrue(NoteCardPolicy(showEngagement = false, showReactions = true).showReactions)
    }
}
