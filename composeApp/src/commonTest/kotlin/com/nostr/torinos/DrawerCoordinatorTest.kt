package com.nostr.torinos

import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import com.nostr.torinos.emoji.EmojiSetAddress
import com.nostr.torinos.ui.components.CustomEmojiOpenRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DrawerCoordinatorTest {
    @Test
    fun emojiDetailReturnsToProfileAndPreservesRequest() = runTest {
        val coordinator = DrawerCoordinator(DrawerState(DrawerValue.Closed), DrawerState(DrawerValue.Closed), this)
        coordinator.updateProfileDocking(true)
        coordinator.openProfile("author")
        runCurrent()
        val profile = ProfileDrawerDestination.Profile("author")
        val request = CustomEmojiOpenRequest.of("bird", "https://example.com/bird.png", EmojiSetAddress("a".repeat(64), "birds"))

        coordinator.openCustomEmoji(profile, request)
        runCurrent()
        assertEquals(ProfileDrawerDestination.CustomEmoji(request), coordinator.profileDestination)
        assertTrue(coordinator.isProfileContentReady)

        coordinator.navigateBackOrCloseProfile()
        runCurrent()
        assertEquals(profile, coordinator.profileDestination)
    }

    @Test
    fun emojiFromThreadReturnsToThreadAndIgnoresStaleSource() = runTest {
        val coordinator = DrawerCoordinator(DrawerState(DrawerValue.Closed), DrawerState(DrawerValue.Closed), this)
        coordinator.updateProfileDocking(true)
        coordinator.openProfile("author")
        runCurrent()
        val profile = ProfileDrawerDestination.Profile("author")
        coordinator.openThread(profile, "note")
        runCurrent()
        val thread = ProfileDrawerDestination.Thread("note")
        val request = CustomEmojiOpenRequest.of("bird")

        coordinator.openCustomEmoji(profile, request)
        runCurrent()
        assertEquals(thread, coordinator.profileDestination)
        coordinator.openCustomEmoji(thread, request)
        runCurrent()
        coordinator.navigateBackOrCloseProfile()
        runCurrent()
        assertEquals(thread, coordinator.profileDestination)
        coordinator.navigateBackOrCloseProfile()
        runCurrent()
        assertEquals(profile, coordinator.profileDestination)
    }
}
