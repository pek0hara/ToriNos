package com.nostr.torinos.chat

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.decodeNostrEventReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupMessagesTest {
    private val relay = "wss://groups.example.com"
    private val me = "a".repeat(64)
    private val other = "b".repeat(64)
    private val parentId = "1".repeat(64)

    private fun event(
        id: String,
        kind: Int = GROUP_CHAT_KIND,
        tags: List<List<String>> = listOf(listOf("h", "g1")),
        pubkey: String = other,
        createdAt: Long = 100,
        content: String = "",
    ) = NostrEvent(id, pubkey, createdAt, kind, tags, content, "sig")

    @Test
    fun chatMessageCarriesGroupTagAndPreviousReferences() {
        val draft = buildGroupChatMessage(
            groupId = "g1",
            text = "こんにちは",
            replyTo = null,
            previous = listOf("aaaaaaaa", "bbbbbbbb", "cccccccc"),
        )

        assertEquals(GROUP_CHAT_KIND, draft.kind)
        assertEquals("こんにちは", draft.content)
        assertEquals(listOf("h", "g1"), draft.tags.first())
        assertEquals(listOf("previous", "aaaaaaaa", "bbbbbbbb", "cccccccc"), draft.tags.single { it[0] == "previous" })
    }

    @Test
    fun noPreviousTagWhenNothingWasSeen() {
        val draft = buildGroupChatMessage("g1", "hi", null, emptyList())
        assertTrue(draft.tags.none { it[0] == "previous" })
    }

    @Test
    fun replyQuotesParentWithQTagAndNevent() {
        val parent = event(parentId, pubkey = other)
        val draft = buildGroupChatMessage("g1", "そうですね", GroupReplyTarget(parent, relay), emptyList())

        assertEquals(listOf("q", parentId, relay, other), draft.tags.single { it[0] == "q" })
        assertTrue(listOf("p", other) in draft.tags)
        val firstLine = draft.content.lineSequence().first()
        assertTrue(firstLine.startsWith("nostr:nevent1"), draft.content)
        assertEquals(parentId, decodeNostrEventReference(firstLine.removePrefix("nostr:"))?.eventId)
        assertTrue(draft.content.endsWith("\nそうですね"))
    }

    @Test
    fun blankMessageIsRejected() {
        assertFailsWith<IllegalArgumentException> { buildGroupChatMessage("g1", "  \n ", null, emptyList()) }
    }

    @Test
    fun replyTargetIsReadFromQTag() {
        val reply = event("2".repeat(64), tags = listOf(listOf("h", "g1"), listOf("q", parentId, relay, other)))
        assertEquals(parentId, groupReplyTargetId(reply))
        assertNull(groupReplyTargetId(event("3".repeat(64))))
    }

    @Test
    fun replyTextHidesTheLeadingQuoteReference() {
        val parent = event(parentId)
        val draft = buildGroupChatMessage("g1", "本文", GroupReplyTarget(parent, relay), emptyList())
        val reply = event("2".repeat(64), tags = draft.tags, content = draft.content)

        assertEquals("本文", groupMessageBody(reply))
    }

    @Test
    fun previousReferencesUseNewestSeenEventsFromThisRelay() {
        val seen = PreviousReferenceWindow(capacity = 50)
        listOf("11111111aaaa", "22222222bbbb", "33333333cccc", "44444444dddd").forEach(seen::add)

        assertEquals(listOf("44444444", "33333333", "22222222"), seen.pick(3))
    }

    @Test
    fun previousWindowKeepsOnlyTheLatestEvents() {
        val seen = PreviousReferenceWindow(capacity = 2)
        listOf("11111111", "22222222", "33333333").forEach(seen::add)
        seen.add("33333333")

        assertEquals(listOf("33333333", "22222222"), seen.pick(5))
    }

    @Test
    fun joinAndLeaveRequests() {
        assertEquals(
            listOf(listOf("h", "g1"), listOf("code", "secret")),
            buildGroupJoinRequest("g1", inviteCode = "secret", reason = "").tags,
        )
        assertEquals(listOf(listOf("h", "g1")), buildGroupJoinRequest("g1", null, "").tags)
        assertEquals(GROUP_JOIN_REQUEST_KIND, buildGroupJoinRequest("g1", null, "").kind)
        assertEquals(GROUP_LEAVE_REQUEST_KIND, buildGroupLeaveRequest("g1").kind)
    }

    @Test
    fun customReactionCarriesEmojiTag() {
        val target = event(parentId, pubkey = other)
        val address = com.nostr.torinos.emoji.EmojiSetAddress(me, "set")
        val option = com.nostr.torinos.model.ReactionOption.Custom("wave", "https://x/w.png", address)

        val standard = buildGroupReaction("g1", target, option, GroupFlavor.Standard)
        assertEquals(":wave:", standard.content)
        assertTrue(listOf("emoji", "wave", "https://x/w.png", address.value) in standard.tags)
        assertTrue(listOf("h", "g1") in standard.tags)
        assertTrue(listOf("e", parentId) in standard.tags)

        // Buzz は shortcode を小文字にそろえる（リレーは正規化して検証し、他のクライアントも小文字で照合する）。
        val buzz = buildGroupReaction("g1", target, com.nostr.torinos.model.ReactionOption.Custom("Wave", "https://x/w.png"), GroupFlavor.Buzz)
        assertEquals(":wave:", buzz.content)
        assertTrue(listOf("emoji", "wave", "https://x/w.png") in buzz.tags)
    }

    @Test
    fun unicodeReactionHasNoEmojiTag() {
        val target = event(parentId, pubkey = other)
        val draft = buildGroupReaction("g1", target, com.nostr.torinos.model.ReactionOption.Unicode("🎉"), GroupFlavor.Buzz)
        assertEquals("🎉", draft.content)
        assertTrue(draft.tags.none { it.first() == "emoji" })
    }

    @Test
    fun reactionAndDeletionCarryGroupTag() {
        val target = event(parentId, pubkey = other)

        val reaction = buildGroupReaction("g1", target, com.nostr.torinos.model.ReactionOption.Unicode("+"), GroupFlavor.Standard)
        assertEquals(7, reaction.kind)
        assertTrue(listOf("h", "g1") in reaction.tags)
        assertTrue(listOf("e", parentId) in reaction.tags)
        assertTrue(listOf("p", other) in reaction.tags)
        assertTrue(listOf("k", "9") in reaction.tags)

        val deletion = buildGroupDeletion("g1", listOf(parentId))
        assertEquals(5, deletion.kind)
        assertEquals(listOf(listOf("h", "g1"), listOf("e", parentId)), deletion.tags)
    }

    @Test
    fun publishGuardAcceptsOnlyGroupEventsToTheGroupRelay() {
        val ok = event("4".repeat(64), pubkey = me)
        assertNull(groupPublishViolation(ok, groupId = "g1", targets = setOf(relay), groupRelay = relay))

        assertEquals(
            GroupPublishViolation.WrongRelay,
            groupPublishViolation(ok, "g1", setOf(relay, "wss://other.example"), relay),
        )
        assertEquals(
            GroupPublishViolation.MissingGroupTag,
            groupPublishViolation(event("5".repeat(64), tags = emptyList()), "g1", setOf(relay), relay),
        )
        assertEquals(
            GroupPublishViolation.MissingGroupTag,
            groupPublishViolation(event("6".repeat(64), tags = listOf(listOf("h", "g2"))), "g1", setOf(relay), relay),
        )
        assertEquals(
            GroupPublishViolation.UnsupportedKind,
            groupPublishViolation(event("7".repeat(64), kind = 1), "g1", setOf(relay), relay),
        )
    }

    @Test
    fun joinRejectionTellsPendingReviewFromRefusal() {
        // NIP-29: まだ追加していない参加要求は OK false で返り、審査待ちか確定かはメッセージで伝える。
        assertEquals(JoinRejection.AlreadyMember, classifyJoinRejection("duplicate: already a member"))
        assertEquals(JoinRejection.PendingReview, classifyJoinRejection("blocked: your request is pending review"))
        assertEquals(JoinRejection.PendingReview, classifyJoinRejection("Waiting for admin approval"))
        assertEquals(JoinRejection.Refused, classifyJoinRejection("restricted: invite code required"))
        assertEquals(JoinRejection.Refused, classifyJoinRejection("blocked: payment required"))
        assertEquals(JoinRejection.Refused, classifyJoinRejection(""))
    }
}
