package com.nostr.torinos

import com.nostr.torinos.model.NoteContext
import com.nostr.torinos.ui.post.PostSheetInitialState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegistrationOnboardingTest {
    @Test
    fun registrationSurvivesSessionRecreationAndTakesPriorityOverPendingPost() {
        val requests = PendingComposerRequestHolder()
        requests.requestNewPost()
        requests.requestRegistration("new-account")
        val coordinator = ComposerCoordinator(requests)
        var resetCount = 0

        coordinator.resumeAfterKeySetup("new-account", true) { resetCount++ }

        assertTrue(coordinator.showRegistrationProfile)
        assertEquals(ComposerPresentation.Hidden, coordinator.presentation)
        assertEquals(0, resetCount)
        assertFalse(requests.consumeNewPost())
        assertNull(requests.registeringPubkey)
    }

    @Test
    fun savingOrSkippingProfileOpensGreetingSheetOnce() {
        val requests = PendingComposerRequestHolder()
        requests.requestRegistration("new-account")
        val coordinator = ComposerCoordinator(requests)
        coordinator.resumeAfterKeySetup("new-account", true) { }
        coordinator.quoteToId = "previous-quote"
        coordinator.quoteToPubkey = "previous-author"
        coordinator.quoteToPreview = "previous-preview"
        coordinator.replyNoteContext = NoteContext.Channel("previous-channel")
        var text: String? = null
        var prepareCount = 0

        coordinator.completeRegistration { text = it; prepareCount++ }
        coordinator.completeRegistration { prepareCount++ }

        assertEquals("Nostr、はじめました🐦", text)
        assertEquals(1, prepareCount)
        assertFalse(coordinator.showRegistrationProfile)
        assertEquals(ComposerPresentation.FullScreen, coordinator.presentation)
        assertEquals(PostSheetInitialState.KeepCurrent, coordinator.sheetInitialState)
        assertNull(coordinator.localDraft)
        assertNull(coordinator.replyTarget)
        assertNull(coordinator.quoteToId)
        assertEquals(NoteContext.Timeline, coordinator.replyNoteContext)
    }

    @Test
    fun registrationAlsoUsesSheetWhenFooterComposerIsDisabled() {
        val requests = PendingComposerRequestHolder()
        requests.requestRegistration("new-account")
        val coordinator = ComposerCoordinator(requests)
        coordinator.resumeAfterKeySetup("new-account", false) { }
        coordinator.completeRegistration { }
        assertEquals(ComposerPresentation.FullScreen, coordinator.presentation)
    }

    @Test
    fun loginWithoutRegistrationKeepsExistingPostResumeBehavior() {
        val requests = PendingComposerRequestHolder()
        requests.requestNewPost()
        val coordinator = ComposerCoordinator(requests)
        var resetCount = 0
        coordinator.resumeAfterKeySetup("existing-account", true) { resetCount++ }
        assertFalse(coordinator.showRegistrationProfile)
        assertEquals(ComposerPresentation.FeedInline, coordinator.presentation)
        assertEquals(1, resetCount)
    }

    @Test
    fun normalLoginDoesNotOpenOnboardingOrComposer() {
        val coordinator = ComposerCoordinator()
        coordinator.resumeAfterKeySetup("existing-account", true) { error("Unexpected reset") }
        assertFalse(coordinator.showRegistrationProfile)
        assertEquals(ComposerPresentation.Hidden, coordinator.presentation)
    }

    @Test
    fun registrationRequestOnlyMatchesItsAccountAndIsConsumedOnce() {
        val requests = PendingComposerRequestHolder()
        requests.requestRegistration("new-account")
        assertFalse(requests.consumeRegistration("other-account"))
        assertTrue(requests.consumeRegistration("new-account"))
        assertFalse(requests.consumeRegistration("new-account"))
    }

    @Test
    fun failedRegistrationAndCancelledSetupClearOnboardingRequest() {
        val requests = PendingComposerRequestHolder()
        requests.requestRegistration("new-account")
        requests.requestRegistration(null)
        assertFalse(requests.consumeRegistration("new-account"))
        requests.requestRegistration("new-account")
        val coordinator = ComposerCoordinator(requests)
        coordinator.dismissKeySetup()
        assertFalse(requests.consumeRegistration("new-account"))
    }

    @Test
    fun recompositionDoesNotRestartCompletedOnboarding() {
        val requests = PendingComposerRequestHolder()
        requests.requestRegistration("new-account")
        val coordinator = ComposerCoordinator(requests)
        coordinator.resumeAfterKeySetup("new-account", true) { }
        coordinator.completeRegistration { }
        coordinator.dismissPost()
        coordinator.resumeAfterKeySetup("new-account", true) { error("Unexpected reset") }
        assertFalse(coordinator.showRegistrationProfile)
        assertEquals(ComposerPresentation.Hidden, coordinator.presentation)
    }
}
