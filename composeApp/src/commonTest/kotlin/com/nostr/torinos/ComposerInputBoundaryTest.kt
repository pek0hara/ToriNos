package com.nostr.torinos

import com.nostr.torinos.ui.post.PostMemoData
import com.nostr.torinos.ui.post.PostViewModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ComposerInputBoundaryTest {
    @Test fun expandingSameInputDoesNotChangeInputGeneration() {
        val composer = ComposerCoordinator()
        composer.openNewPost {}
        val generation = composer.inputGeneration
        val opening = composer.openingGeneration
        composer.expandInline()
        assertEquals(generation, composer.inputGeneration)
        assertTrue(composer.openingGeneration > opening)
        composer.dismissPost()
        assertTrue(composer.inputGeneration > generation)
    }

    @Test fun replacementBlocksEditingButAllowsItsOwnRestore() {
        val post = PostViewModel()
        post.onTextChange("before")
        val release = post.beginInputReplacement()
        post.onTextChange("unexpected")
        assertEquals("before", post.state.value.text)
        assertFalse(post.state.value.canPost)
        post.restoreInputMemo(PostMemoData("restored", emptyList(), null, null, 1, null, 1))
        assertEquals("restored", post.state.value.text)
        assertFalse(post.state.value.canPost)
        release()
        post.onTextChange("after")
        assertEquals("after", post.state.value.text)
        assertTrue(post.state.value.canPost)
    }

    @Test fun delayedOldReplyCannotReplaceNewRequest() = runTest {
        val resolver = ReplyComposerResolver(backgroundScope)
        val applied = mutableListOf<String>()
        resolver.resolve(load = { withContext(NonCancellable) { delay(100); "old" } }) { applied += it }
        runCurrent()
        resolver.resolve(load = { "new" }) { applied += it }
        runCurrent()
        delay(200)
        assertEquals(listOf("new"), applied)
        resolver.cancel()
    }

    @Test fun delayedReplyCannotReplaceEditedInputWithinSameGeneration() = runTest {
        val composer = ComposerCoordinator()
        val post = PostViewModel()
        composer.openNewPost {}
        val generation = composer.inputGeneration
        val revision = post.editorRevision
        val resolver = ReplyComposerResolver(backgroundScope)
        var applied = false
        resolver.resolve(load = { delay(100); "reply" }, isCurrentInput = {
            composer.inputGeneration == generation && post.editorRevision == revision
        }) { applied = true }
        runCurrent()
        post.onTextChange("new text")
        delay(200)
        assertFalse(applied)
        assertEquals("new text", post.state.value.text)
        resolver.cancel()
    }

    @Test fun delayedReplyCannotReplaceInputWhileItIsBeingSaved() = runTest {
        val post = PostViewModel()
        val resolver = ReplyComposerResolver(backgroundScope) { !post.state.value.isFrozen }
        var applied = false
        resolver.resolve(load = { delay(100); "reply" }) { applied = true }
        runCurrent()
        val release = post.beginInputReplacement()
        delay(200)
        assertFalse(applied)
        release()
        resolver.cancel()
    }

    @Test fun footerSelectionRevealsSameTabAndClearsOrphans() {
        assertFalse(footerNavigationPlan("feed", "feed").navigate)
        assertTrue(footerNavigationPlan("services", "feed").resetFeedChrome)
        assertTrue(footerNavigationPlan(null, "journal").clearOrphanPages)
    }
}
