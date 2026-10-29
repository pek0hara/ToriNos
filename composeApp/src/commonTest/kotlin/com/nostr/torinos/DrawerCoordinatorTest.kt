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
import kotlin.test.assertNull
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

    @Test
    fun staleClosedStateFromPreviousSessionDoesNotClearReopenedProfile() = runTest {
        val profileState = DrawerState(DrawerValue.Closed)
        val coordinator = DrawerCoordinator(DrawerState(DrawerValue.Closed), profileState, this)

        // 1回目: 開き切ったことを記録する。
        coordinator.openProfile("first")
        runCurrent()
        coordinator.onProfileStateChanged()
        runCurrent()

        // 閉じ切ったが、その状態変化の通知はまだ処理されていない。
        profileState.snapTo(DrawerValue.Closed)

        // すぐに開き直す。開くアニメーションは連続タップのドラッグで中断され、
        // 前回分の遅れた通知が処理される時点ではまだ Closed に留まっている。
        coordinator.openProfile("second")
        runCurrent()
        profileState.snapTo(DrawerValue.Closed)
        coordinator.onProfileStateChanged()
        runCurrent()

        // 指を離すとドロワーは開いた側に吸い付く。
        profileState.snapTo(DrawerValue.Open)
        coordinator.onProfileStateChanged()
        runCurrent()

        assertEquals(ProfileDrawerDestination.Profile("second"), coordinator.profileDestination)
        assertTrue(coordinator.isProfileContentReady)
    }

    @Test
    fun openDrawerWithoutDestinationCloses() = runTest {
        // 表示先のないドロワーが開いたままになると、中身のない白い画面で止まる。
        val profileState = DrawerState(DrawerValue.Open)
        val coordinator = DrawerCoordinator(DrawerState(DrawerValue.Closed), profileState, this)

        coordinator.onProfileStateChanged()
        runCurrent()

        assertNull(coordinator.profileDestination)
        assertEquals(DrawerValue.Closed, profileState.currentValue)
    }

    @Test
    fun systemBackClosesNotificationsBeforeProfile() {
        assertEquals(DrawerBackTarget.Notifications, drawerBackTarget(notificationsOpen = true, profileOpen = true))
        assertEquals(DrawerBackTarget.Notifications, drawerBackTarget(notificationsOpen = true, profileOpen = false))
        assertEquals(DrawerBackTarget.Profile, drawerBackTarget(notificationsOpen = false, profileOpen = true))
        assertNull(drawerBackTarget(notificationsOpen = false, profileOpen = false))
    }

    @Test
    fun systemBackTargetFollowsOpenDrawers() = runTest {
        val closed = DrawerCoordinator(DrawerState(DrawerValue.Closed), DrawerState(DrawerValue.Closed), this)
        assertNull(closed.systemBackTarget)

        val profileOpen = DrawerCoordinator(DrawerState(DrawerValue.Closed), DrawerState(DrawerValue.Open), this)
        assertEquals(DrawerBackTarget.Profile, profileOpen.systemBackTarget)

        val notificationsOpen = DrawerCoordinator(DrawerState(DrawerValue.Open), DrawerState(DrawerValue.Closed), this)
        assertEquals(DrawerBackTarget.Notifications, notificationsOpen.systemBackTarget)
    }

    @Test
    fun systemBackIgnoresDockedProfile() = runTest {
        // 左パネルに固定したプロフィールは画面の一部なので、システムの戻るは画面側に任せる。
        val coordinator = DrawerCoordinator(DrawerState(DrawerValue.Closed), DrawerState(DrawerValue.Closed), this)
        coordinator.updateProfileDocking(true)
        coordinator.openProfile("author")
        runCurrent()

        assertNull(coordinator.systemBackTarget)
    }
}
