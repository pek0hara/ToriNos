package com.nostr.torinos

import com.nostr.torinos.model.NoteContext
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.ui.post.PostMemoData
import com.nostr.torinos.ui.post.PostSheetInitialState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComposerCoordinatorTest {
    @Test
    fun dismissPostDiscardsDraftAndClearsReplyAndQuoteContext() {
        val coordinator = ComposerCoordinator()
        val draft = memo("draft")
        coordinator.localDraft = draft
        coordinator.prepareReply(event(), "preview", NoteContext.Timeline)
        coordinator.quoteToId = "quote"
        coordinator.openFullScreen()

        coordinator.dismissPost()

        assertNull(coordinator.localDraft)
        assertEquals(ComposerPresentation.Hidden, coordinator.presentation)
        assertNull(coordinator.replyTarget)
        assertNull(coordinator.quoteToId)
        assertEquals(NoteContext.Timeline, coordinator.replyNoteContext)
    }

    @Test
    fun staleReplyResolutionCannotBecomeCurrentAgain() {
        val tracker = ReplyResolutionTracker()
        val first = tracker.begin()
        tracker.invalidate()
        val second = tracker.begin()

        assertFalse(tracker.isCurrent(first))
        assertEquals(true, tracker.isCurrent(second))
    }

    @Test
    fun feedPlusOpensInlineComposerAndResetsPostStateOnce() {
        val coordinator = ComposerCoordinator()
        var resetCount = 0

        coordinator.openNewPost { resetCount++ }

        assertEquals(ComposerPresentation.FeedInline, coordinator.presentation)
        assertEquals(1, resetCount)
    }

    @Test
    fun feedPlusClearsPreviousReplyAndQuoteContext() {
        val coordinator = ComposerCoordinator()
        coordinator.prepareReply(event(), "preview", NoteContext.Timeline)
        coordinator.quoteToId = "quote"
        coordinator.quoteToPubkey = "author"
        coordinator.quoteToPreview = "quoted"

        coordinator.openNewPost { }

        assertNull(coordinator.replyTarget)
        assertNull(coordinator.replyToPreview)
        assertNull(coordinator.quoteToId)
        assertNull(coordinator.quoteToPubkey)
        assertNull(coordinator.quoteToPreview)
        assertEquals(NoteContext.Timeline, coordinator.replyNoteContext)
    }

    @Test
    fun feedPlusWithLocalDraftRestoresItInTheSheetInsteadOfDiscardingIt() {
        val coordinator = ComposerCoordinator()
        val draft = memo("絵文字設定から戻った本文")
        coordinator.localDraft = draft
        var resetCount = 0

        coordinator.openNewPost { resetCount++ }

        assertEquals(ComposerPresentation.FullScreen, coordinator.presentation)
        assertEquals(PostSheetInitialState.RestoreMemo, coordinator.sheetInitialState)
        assertEquals(draft, coordinator.localDraft)
        assertEquals(0, resetCount)
    }

    @Test
    fun expandingInlineComposerKeepsCurrentTextAndOpensFullScreen() {
        val coordinator = ComposerCoordinator()
        coordinator.openNewPost { }

        coordinator.expandInline()

        assertEquals(ComposerPresentation.FullScreen, coordinator.presentation)
        assertEquals(PostSheetInitialState.KeepCurrent, coordinator.sheetInitialState)
    }

    @Test
    fun expandIsIgnoredUnlessInlineComposerIsOpen() {
        val coordinator = ComposerCoordinator()

        coordinator.expandInline()
        assertEquals(ComposerPresentation.Hidden, coordinator.presentation)

        coordinator.openFullScreen()
        coordinator.expandInline()
        assertEquals(PostSheetInitialState.Reset, coordinator.sheetInitialState)
    }

    @Test
    fun replyAndQuoteOpenFullScreenWithReset() {
        val coordinator = ComposerCoordinator()
        coordinator.openNewPost { }
        coordinator.expandInline()

        coordinator.openFullScreen()

        assertEquals(ComposerPresentation.FullScreen, coordinator.presentation)
        assertEquals(PostSheetInitialState.Reset, coordinator.sheetInitialState)
    }

    @Test
    fun closingInlineWhileIdleDiscardsThePostState() {
        val coordinator = ComposerCoordinator()
        coordinator.openNewPost { }
        var resetCount = 0

        coordinator.closeInline(isPosting = false) { resetCount++ }

        assertEquals(ComposerPresentation.Hidden, coordinator.presentation)
        assertEquals(1, resetCount)
    }

    @Test
    fun closingInlineWhilePostingKeepsThePostStateSoTheResultIsNotLost() {
        val coordinator = ComposerCoordinator()
        coordinator.openNewPost { }
        var resetCount = 0

        coordinator.closeInline(isPosting = true) { resetCount++ }

        assertEquals(ComposerPresentation.Hidden, coordinator.presentation)
        assertEquals(0, resetCount)
    }

    @Test
    fun closingInlineDoesNothingWhenTheSheetIsOpen() {
        val coordinator = ComposerCoordinator()
        coordinator.openFullScreen()
        var resetCount = 0

        coordinator.closeInline(isPosting = false) { resetCount++ }

        assertEquals(ComposerPresentation.FullScreen, coordinator.presentation)
        assertEquals(0, resetCount)
    }

    @Test
    fun plusWithoutFooterComposerOpensTheSheetAsBefore() {
        val coordinator = ComposerCoordinator()
        coordinator.prepareReply(event(), "preview", NoteContext.Timeline)
        var resetCount = 0

        coordinator.openNewPost(useFooterComposer = false) { resetCount++ }

        assertEquals(ComposerPresentation.FullScreen, coordinator.presentation)
        assertEquals(PostSheetInitialState.Reset, coordinator.sheetInitialState)
        assertNull(coordinator.replyTarget)
        // 初期化はシート側の Reset が行う。
        assertEquals(0, resetCount)
    }

    @Test
    fun switchingToTheMenuHidesTheInlineComposerAndKeepsTheInput() {
        val coordinator = ComposerCoordinator()
        var resetCount = 0
        coordinator.openNewPost { resetCount++ }

        coordinator.switchInlineToMenu()

        assertEquals(ComposerPresentation.Hidden, coordinator.presentation)
        assertTrue(coordinator.hasHeldInlineDraft)
        assertEquals(1, resetCount)
    }

    @Test
    fun plusAfterSwitchingToTheMenuReopensTheSameInputWithoutReset() {
        val coordinator = ComposerCoordinator()
        var resetCount = 0
        coordinator.openNewPost { resetCount++ }
        coordinator.switchInlineToMenu()

        coordinator.openNewPost { resetCount++ }

        assertEquals(ComposerPresentation.FeedInline, coordinator.presentation)
        assertFalse(coordinator.hasHeldInlineDraft)
        assertEquals(1, resetCount)
    }

    @Test
    fun switchingToTheMenuIsIgnoredUnlessInlineComposerIsOpen() {
        val coordinator = ComposerCoordinator()
        coordinator.switchInlineToMenu()
        assertFalse(coordinator.hasHeldInlineDraft)

        coordinator.openFullScreen()
        coordinator.switchInlineToMenu()
        assertEquals(ComposerPresentation.FullScreen, coordinator.presentation)
        assertFalse(coordinator.hasHeldInlineDraft)
    }

    @Test
    fun leavingTheFeedDiscardsAHeldInput() {
        val coordinator = ComposerCoordinator()
        coordinator.openNewPost { }
        coordinator.switchInlineToMenu()
        var resetCount = 0

        coordinator.closeInline(isPosting = false) { resetCount++ }

        assertEquals(ComposerPresentation.Hidden, coordinator.presentation)
        assertFalse(coordinator.hasHeldInlineDraft)
        assertEquals(1, resetCount)
    }

    @Test
    fun replyAfterSwitchingToTheMenuStartsFreshAndEndsTheHold() {
        val coordinator = ComposerCoordinator()
        coordinator.openNewPost { }
        coordinator.switchInlineToMenu()

        coordinator.prepareReply(event(), "preview", NoteContext.Timeline)
        coordinator.openFullScreen()

        assertEquals(PostSheetInitialState.Reset, coordinator.sheetInitialState)
        assertFalse(coordinator.hasHeldInlineDraft)
    }

    @Test
    fun postCompletionEndsTheHold() {
        // 送信中に「…」で切り替え、そのまま成功した場合。
        val coordinator = ComposerCoordinator()
        coordinator.openNewPost { }
        coordinator.switchInlineToMenu()

        coordinator.dismissPost()

        assertFalse(coordinator.hasHeldInlineDraft)
        var resetCount = 0
        coordinator.openNewPost { resetCount++ }
        assertEquals(1, resetCount)
    }

    @Test
    fun hidingForCustomEmojiSettingsKeepsTheLocalDraft() {
        val coordinator = ComposerCoordinator()
        val draft = memo("draft")
        coordinator.openFullScreen()
        coordinator.localDraft = draft

        coordinator.hide()

        assertEquals(ComposerPresentation.Hidden, coordinator.presentation)
        assertEquals(draft, coordinator.localDraft)
    }

    @Test
    fun newPostRequestIsConsumedExactlyOnce() {
        val holder = PendingComposerRequestHolder()
        assertFalse(holder.consumeNewPost())

        holder.requestNewPost()

        assertTrue(holder.consumeNewPost())
        assertFalse(holder.consumeNewPost())
    }

    @Test
    fun cancellingKeySetupDiscardsThePendingNewPostRequest() {
        val holder = PendingComposerRequestHolder()
        val coordinator = ComposerCoordinator(holder)
        holder.requestNewPost()
        coordinator.pendingKeyAction = PendingKeyAction.NewPost
        coordinator.showKeySetup = true

        coordinator.dismissKeySetup()

        assertFalse(coordinator.showKeySetup)
        assertNull(coordinator.pendingKeyAction)
        assertFalse(holder.consumeNewPost())
        assertEquals(ComposerPresentation.Hidden, coordinator.presentation)
    }

    @Test
    fun pendingRequestSurvivesRecreationOfTheCoordinator() {
        val holder = PendingComposerRequestHolder()
        ComposerCoordinator(holder).pendingRequests.requestNewPost()

        // 鍵設定の完了でセッションが作り直され、新しいコーディネーターが同じホルダーを受け取る。
        val recreated = ComposerCoordinator(holder)

        assertTrue(recreated.pendingRequests.consumeNewPost())
        assertFalse(recreated.pendingRequests.consumeNewPost())
    }

    @Test
    fun newPostWithFooterDisabledClearsReplyAndQuoteContext() {
        val coordinator = ComposerCoordinator()
        coordinator.prepareReply(event(), "preview", NoteContext.Timeline)

        coordinator.quoteToId = "quote"
        coordinator.quoteToPubkey = "author"
        coordinator.quoteToPreview = "quoted"

        coordinator.openNewPost(useFooterComposer = false) { }

        assertNull(coordinator.quoteToId)
        assertNull(coordinator.quoteToPubkey)
        assertNull(coordinator.quoteToPreview)
        assertNull(coordinator.replyTarget)
        assertNull(coordinator.replyToPreview)
        assertEquals(ComposerPresentation.FullScreen, coordinator.presentation)
        assertEquals(PostSheetInitialState.Reset, coordinator.sheetInitialState)
    }

    @Test
    fun localDraftRestoresInTheSheetWithEitherFooterSetting() {
        for (useFooterComposer in listOf(false, true)) {
            val coordinator = ComposerCoordinator()
            val draft = memo("絵文字設定から戻った本文")
            coordinator.localDraft = draft
            var resetCount = 0

            coordinator.openNewPost(useFooterComposer) { resetCount++ }

            assertEquals(ComposerPresentation.FullScreen, coordinator.presentation)
            assertEquals(PostSheetInitialState.RestoreMemo, coordinator.sheetInitialState)
            assertEquals(draft, coordinator.localDraft)
            assertEquals(0, resetCount)
        }
    }

    @Test
    fun applyingPendingInputSuppressesOnlyThatOpeningAndNextPostPromptsAgain() {
        val coordinator = ComposerCoordinator()
        coordinator.openFullScreen(PostSheetInitialState.KeepCurrent, offerPendingInputs = false)
        val appliedOpening = coordinator.openingGeneration
        assertFalse(coordinator.shouldOfferPendingInputs(appliedOpening))
        coordinator.dismissPost()
        coordinator.openNewPost(useFooterComposer = false) { }
        assertTrue(coordinator.shouldOfferPendingInputs(coordinator.openingGeneration))
        assertFalse(coordinator.shouldOfferPendingInputs(appliedOpening))
    }

    @Test
    fun latePendingInputLookupFromClosedOrReplacedSheetCannotReappear() {
        val coordinator = ComposerCoordinator()
        coordinator.openFullScreen()
        val opening = coordinator.openingGeneration
        assertTrue(coordinator.shouldOfferPendingInputs(opening))
        coordinator.hide()
        assertFalse(coordinator.shouldOfferPendingInputs(opening))
        coordinator.openFullScreen()
        assertFalse(coordinator.shouldOfferPendingInputs(opening))
        assertTrue(coordinator.shouldOfferPendingInputs(coordinator.openingGeneration))
    }

    @Test
    fun reopeningInlineComposerCreatesASeparatePendingInputPromptRequest() {
        val coordinator = ComposerCoordinator()
        coordinator.openNewPost { }
        val opening = coordinator.openingGeneration
        coordinator.switchInlineToMenu()
        coordinator.openNewPost { error("must preserve the inline input") }
        assertFalse(coordinator.shouldOfferPendingInputs(opening))
        assertTrue(coordinator.shouldOfferPendingInputs(coordinator.openingGeneration))
    }

    @Test
    fun clearedVisibleInputDoesNotResurrectTheOriginallyRestoredMemo() {
        val coordinator = ComposerCoordinator()
        val original = memo("復元した本文")
        coordinator.localDraft = original
        coordinator.openFullScreen(PostSheetInitialState.KeepCurrent)
        assertNull(coordinator.currentInputSnapshot(null))
        val edited = memo("編集した本文")
        assertEquals(edited, coordinator.currentInputSnapshot(edited))
        coordinator.hide()
        assertEquals(original, coordinator.currentInputSnapshot(null))
    }

    @Test
    fun closedSheetDoesNotParkStaleViewModelTextButExplicitInlineHoldDoes() {
        val coordinator = ComposerCoordinator()
        val stale = memo("閉じた投稿の古い本文")
        coordinator.openFullScreen()
        coordinator.dismissPost()
        assertNull(coordinator.currentInputSnapshot(stale))

        coordinator.openNewPost { }
        coordinator.switchInlineToMenu()
        val held = memo("明示的に保持した本文")
        assertEquals(held, coordinator.currentInputSnapshot(held))

        coordinator.closeInline(isPosting = false) { }
        val saved = memo("絵文字設定へ移動中の入力")
        coordinator.localDraft = saved
        assertEquals(saved, coordinator.currentInputSnapshot(stale))
    }

    @Test
    fun selectedDraftQuotesOverrideTheOpeningQuoteDuringSnapshot() {
        val coordinator = ComposerCoordinator()
        coordinator.prepareQuote(event())
        coordinator.openFullScreen()
        val id = "11".repeat(32)
        val selected = memo("").copy(quotes = listOf(com.nostr.torinos.ui.post.PostQuote(
            id, "nostr:${com.nostr.torinos.model.encodeNevent(id)}", "別の引用")))
        assertEquals(selected, coordinator.currentInputSnapshot(selected))
        assertNull(coordinator.currentInputSnapshot(selected)!!.quoteEventId)
    }

    @Test
    fun selectingAnUnquotedDraftDoesNotResurrectTheOpeningQuote() {
        val coordinator = ComposerCoordinator()
        coordinator.prepareQuote(event())
        coordinator.openFullScreen()
        val selected = memo("引用なしの下書き")
        assertEquals(selected, coordinator.currentInputSnapshot(selected))
    }

    @Test
    fun clearingAnInitializedEditorDoesNotResurrectItsOpeningQuote() {
        val coordinator = ComposerCoordinator()
        coordinator.prepareQuote(event())
        coordinator.openFullScreen()
        assertEquals(event().id, coordinator.currentInputSnapshot(null)!!.quoteEventId)
        coordinator.markInputInitialized()
        assertNull(coordinator.currentInputSnapshot(null))
        coordinator.openFullScreen()
        assertEquals(event().id, coordinator.currentInputSnapshot(null)!!.quoteEventId)
    }

    private fun memo(text: String) = PostMemoData(
        text = text,
        imageUrls = emptyList(),
        replyToId = null,
        replyToPubkey = null,
        noteKind = 1,
        channelId = null,
        updatedAt = 0L,
    )

    private fun event() = NostrEvent(
        id = "event",
        pubkey = "author",
        createdAt = 0L,
        kind = 1,
        tags = emptyList(),
        content = "content",
        sig = "sig",
    )
}
