package com.nostr.torinos.ui.chat

import com.nostr.torinos.chat.ChannelRole
import com.nostr.torinos.chat.CommunityRole
import com.nostr.torinos.chat.GroupFlavor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// docs/buzz-admin-design.md 3.1・3.5
class BuzzAdminUiTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val c = "c".repeat(64)

    @Test
    fun pendingChangesUpdateTheMemberList() {
        assertEquals(listOf(a, c), membersWithPending(listOf(a, b, c), mapOf(b to null)))
        assertEquals(listOf(a, b, c), membersWithPending(listOf(a, b), mapOf(c to ChannelRole.Guest, a to ChannelRole.Admin)))
        assertEquals(listOf(a, b), membersWithPending(listOf(a, b), emptyMap()))
    }

    private val buzz = ChatRelaySummary("wss://png.example", "png", GroupFlavor.Buzz)
    private val standard = ChatRelaySummary("wss://relay.example", null)

    @Test
    fun communityAdminEntryNeedsOwnerOrAdminOfTheSelectedBuzzCommunity() {
        val base = ChatTabUiState(relays = listOf(buzz, standard), selection = ChatSelection.Relay(buzz.relayUrl))
        assertFalse(base.canManageSelectedCommunity)
        assertTrue(base.copy(communityRoles = mapOf(buzz.relayUrl to CommunityRole.Owner)).canManageSelectedCommunity)
        assertTrue(base.copy(communityRoles = mapOf(buzz.relayUrl to CommunityRole.Admin)).canManageSelectedCommunity)
        assertFalse(base.copy(communityRoles = mapOf(buzz.relayUrl to CommunityRole.Member)).canManageSelectedCommunity)
        // 「すべて」やほかのリレーを選んでいるときは出さない。
        val roles = mapOf(buzz.relayUrl to CommunityRole.Owner)
        assertFalse(base.copy(communityRoles = roles, selection = ChatSelection.All).canManageSelectedCommunity)
        assertFalse(base.copy(communityRoles = roles + (standard.relayUrl to CommunityRole.Owner), selection = ChatSelection.Relay(standard.relayUrl)).canManageSelectedCommunity)
    }

    @Test
    fun inviteChoicesStayWithinTheRelayLimits() {
        INVITE_TTL_CHOICES.forEach { (seconds, _) -> com.nostr.torinos.chat.InviteRequest(seconds, 1) }
        INVITE_USES_CHOICES.forEach { (uses, _) -> com.nostr.torinos.chat.InviteRequest(3_600, uses) }
        // 既定（7日・1回）も選択肢にある。
        val sheet = CommunityAdminSheet(relayUrl = buzz.relayUrl, label = "png")
        assertTrue(INVITE_TTL_CHOICES.any { it.first == sheet.inviteTtlSeconds })
        assertTrue(INVITE_USES_CHOICES.any { it.first == sheet.inviteMaxUses })
    }
}
