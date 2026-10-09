package com.nostr.torinos.badge

import kotlin.test.*

class BadgeSheetModelTest {
    private val otherAddress = BadgeAddress(30009, badgeIssuer, "award:two")
    private fun otherPair(id: String = "f".repeat(64)) = BadgeSelectionItem(listOf(listOf("a", otherAddress.value), listOf("e", id)))
    private val setItem = BadgeSelectionItem(listOf(listOf("a", "30008:$badgeOwner:set")))
    private fun draftOf(vararg items: BadgeSelectionItem): BadgeDraft {
        val source = badgeEvent(10008, tags = items.flatMap { it.tags })
        return BadgeDraft(source.id, source, items.toList())
    }

    @Test fun conflictOutranksErrorsAndPartialFetches() {
        val notice = badgeSheetNotice(BadgeSheetTab.Displayed, message = "失敗", profilePartial = true, draftStale = true)
        assertEquals(BadgeNoticeTone.Warning, notice?.tone)
        assertEquals(BadgeNoticeAction.ReloadDraft, notice?.action)
        val stored = badgeSheetNotice(BadgeSheetTab.Displayed, message = BADGE_MESSAGE_CONFLICT)
        assertEquals(BadgeNoticeTone.Warning, stored?.tone)
        assertNull(stored?.action, "a stored conflict is cleared by saving, not by reloading")
    }

    @Test fun staleDraftMessageIsIgnoredOnceTheDraftIsReloaded() {
        assertNull(badgeSheetNotice(BadgeSheetTab.Displayed, message = BADGE_MESSAGE_STALE_DRAFT))
    }

    @Test fun syncStatesAreChipsNotBanners() {
        for (message in listOf(BADGE_MESSAGE_PENDING, BADGE_MESSAGE_PARTIAL_SYNC, BADGE_MESSAGE_SAVED)) {
            assertNull(badgeSheetNotice(BadgeSheetTab.Displayed, message = message), message)
        }
        assertEquals("送信待ち", badgeSyncLabel(BADGE_MESSAGE_PENDING))
        assertEquals("一部未同期", badgeSyncLabel(BADGE_MESSAGE_PARTIAL_SYNC))
        assertNull(badgeSyncLabel(BADGE_MESSAGE_SAVED))
        assertNull(badgeSyncLabel("何かのエラー"))
    }

    @Test fun unknownMessagesAreErrorsWithRetry() {
        val notice = badgeSheetNotice(BadgeSheetTab.Received, message = "授与を確認できないバッジは追加できません")
        assertEquals(BadgeNoticeTone.Error, notice?.tone)
        assertEquals(BadgeNoticeAction.Retry, notice?.action)
        assertEquals("ローカル", badgeSheetNotice(BadgeSheetTab.Displayed, message = "店", localError = "ローカル")?.text)
    }

    @Test fun missingRelaysWarnWithoutAction() {
        val notice = badgeSheetNotice(BadgeSheetTab.Displayed, message = BADGE_MESSAGE_NO_RELAYS)
        assertEquals(BadgeNoticeTone.Warning, notice?.tone)
        assertNull(notice?.action)
    }

    @Test fun partialFetchNoticeDependsOnTab() {
        assertEquals(BadgeNoticeAction.ResolveMore, badgeSheetNotice(BadgeSheetTab.Displayed, profilePartial = true)?.action)
        assertNull(badgeSheetNotice(BadgeSheetTab.Received, profilePartial = true))
        assertEquals(BadgeNoticeAction.Retry, badgeSheetNotice(BadgeSheetTab.Received, awardsIncomplete = true)?.action)
        assertEquals(BadgeNoticeTone.Info, badgeSheetNotice(BadgeSheetTab.Received, message = BADGE_MESSAGE_AWARDS_PARTIAL)?.tone)
        assertNull(badgeSheetNotice(BadgeSheetTab.Displayed, message = BADGE_MESSAGE_AWARDS_PARTIAL))
    }

    @Test fun issuedTabShowsOnlyIssuerState() {
        assertNull(badgeSheetNotice(BadgeSheetTab.Issued, message = "失敗", draftStale = true))
        assertEquals(BadgeNoticeTone.Error, badgeSheetNotice(BadgeSheetTab.Issued, issuerMessage = "x")?.tone)
        assertEquals(BadgeNoticeTone.Info, badgeSheetNotice(BadgeSheetTab.Issued, issuerIncomplete = true)?.tone)
    }

    @Test fun draftIsDirtyOnlyWhenItDiffersFromTheSavedList() {
        val draft = draftOf(badgePair(), otherPair())
        assertFalse(draft.isDirty())
        val swapped = draft.moved(0, 1)
        assertTrue(swapped.isDirty())
        assertEquals(listOf(otherPair(), badgePair()), swapped.items)
        assertFalse(swapped.moved(1, 0).isDirty(), "moving back restores the saved order")
        assertTrue(draft.removedAt(0).isDirty())
        val empty = BadgeDraft(null, null, emptyList())
        assertFalse(empty.isDirty())
    }

    @Test fun moveAndRemoveIgnoreOutOfRangeIndexes() {
        val draft = draftOf(badgePair())
        assertSame(draft, draft.moved(0, 3))
        assertSame(draft, draft.moved(-1, 0))
        assertSame(draft, draft.removedAt(2))
    }

    @Test fun shownStateDistinguishesDirectAndSetEntries() {
        val items = listOf(badgePair(), setItem)
        val expanded = listOf(badgePair(), otherPair())
        assertEquals(BadgeShownState.Shown, badgeShownState(items, expanded, badgeAddress.value))
        assertEquals(BadgeShownState.ShownViaSet, badgeShownState(items, expanded, otherAddress.value))
        assertEquals(BadgeShownState.Hidden, badgeShownState(items, expanded, "30009:$badgeIssuer:none"))
    }

    @Test fun toggleAddsAtTheEndAndRemovesEveryDirectEntry() {
        val draft = draftOf(badgePair())
        val added = draft.toggled(otherAddress.value) { otherPair() }
        assertEquals(listOf(badgePair(), otherPair()), added.items)
        val duplicated = draft.copy(items = listOf(badgePair(), otherPair(), badgePair("1".repeat(64))))
        assertEquals(listOf(otherPair()), duplicated.toggled(badgeAddress.value) { error("must not add") }.items)
    }

    @Test fun rowKeysStayUniqueForRepeatedPairs() {
        val keys = badgeRowKeys(listOf(badgePair(), badgePair(), otherPair()))
        assertEquals(3, keys.toSet().size)
        assertEquals(badgeRowKeys(listOf(badgePair())).single(), keys.first())
    }

    @Test fun dragMovesPastHalfARowAndStopsAtTheEnds() {
        assertEquals(0 to 20f, badgeDragStep(0, 3, 20f, 100f))
        assertEquals(1 to -40f, badgeDragStep(0, 3, 60f, 100f))
        assertEquals(2 to 10f, badgeDragStep(0, 3, 210f, 100f))
        assertEquals(1 to 40f, badgeDragStep(2, 3, -60f, 100f))
        assertEquals(3 to 0f, badgeDragStep(2, 3, 400f, 100f), "the last row cannot be dragged below the list")
        assertEquals(0 to 0f, badgeDragStep(1, 3, -400f, 100f), "the first row cannot be dragged above the list")
        assertEquals(2 to 5f, badgeDragStep(2, 3, 5f, 0f))
    }
}
