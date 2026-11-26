package com.nostr.torinos.ui.notification

import com.nostr.torinos.crypto.Bech32
import com.nostr.torinos.crypto.fromHex
import com.nostr.torinos.crypto.hexToNpub
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.encodeNaddr
import com.nostr.torinos.model.encodeNevent
import com.nostr.torinos.network.TargetFetchFailure
import com.nostr.torinos.network.TargetLoadState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NotificationRelevanceTest {
    private val own = "1".repeat(64)
    private val other = "2".repeat(64)
    private val actor = "3".repeat(64)
    private val parent = "a".repeat(64)
    private val root = "b".repeat(64)
    private val quoted = "c".repeat(64)

    private fun note(tags: List<List<String>>, content: String = "hi", kind: Int = 1) =
        NostrEvent("f".repeat(64), actor, 1, kind, tags, content, "0".repeat(128))

    private fun nprofile(pubkey: String): String =
        Bech32.encode("nprofile", byteArrayOf(0, 32) + pubkey.fromHex() + byteArrayOf(1, 3) + "wss".encodeToByteArray())

    @Test fun pTagOnlyIsUnrelated() {
        val spam = note(listOf(listOf("p", other), listOf("p", own), listOf("p", actor)), "Claim your tokens")
        assertEquals(NotificationRelevance.Unrelated, notificationRelevance(spam, own))
    }

    @Test fun mentionInContentIsRelevant() {
        assertEquals(NotificationRelevance.Relevant,
            notificationRelevance(note(listOf(listOf("p", own)), "nostr:${hexToNpub(own)} おはよ"), own))
        assertEquals(NotificationRelevance.Relevant,
            notificationRelevance(note(listOf(listOf("p", own)), "nostr:${nprofile(own)} こんにちは"), own))
        assertEquals(NotificationRelevance.Unrelated,
            notificationRelevance(note(listOf(listOf("p", own)), "nostr:${hexToNpub(other)} こんにちは"), own))
    }

    @Test fun replyUsesAuthorHintOfTheReplyTarget() {
        val toMe = note(listOf(listOf("e", root, "", "root", other), listOf("e", parent, "", "reply", own), listOf("p", own)))
        assertEquals(NotificationRelevance.Relevant, notificationRelevance(toMe, own))

        // 自分が始めたスレッドでも、他人への返信は出さない。
        val toOther = note(listOf(listOf("e", root, "", "root", own), listOf("e", parent, "", "reply", other), listOf("p", own)))
        assertEquals(NotificationRelevance.Unrelated, notificationRelevance(toOther, own))
    }

    @Test fun replyWithoutAuthorHintNeedsTarget() {
        val reply = note(listOf(listOf("e", root, "", "root"), listOf("e", parent, "", "reply"), listOf("p", own)))
        assertEquals(NotificationRelevance.NeedsTargetAuthor(setOf(parent)), notificationRelevance(reply, own))
    }

    @Test fun quoteOfOwnPostIsRelevant() {
        assertEquals(NotificationRelevance.Relevant,
            notificationRelevance(note(listOf(listOf("q", quoted, "", own), listOf("p", own))), own))
        val nevent = encodeNevent(quoted, authorPubkey = own)
        assertEquals(NotificationRelevance.Relevant,
            notificationRelevance(note(listOf(listOf("p", own)), "これ nostr:$nevent"), own))
        assertEquals(NotificationRelevance.NeedsTargetAuthor(setOf(quoted)),
            notificationRelevance(note(listOf(listOf("q", quoted), listOf("p", own))), own))
        assertEquals(NotificationRelevance.Unrelated,
            notificationRelevance(note(listOf(listOf("q", quoted, "", other), listOf("p", own))), own))
    }

    @Test fun quoteWithoutPTagOrHintOfKnownOwnPostIsRelevant() {
        val quote = note(listOf(listOf("q", quoted, "wss://relay")), "👀\nnostr:${encodeNevent(quoted)}")
        assertEquals(NotificationRelevance.Relevant, notificationRelevance(quote, own, ownEventIds = setOf(quoted)))
        assertEquals(NotificationRelevance.Relevant, notificationRelevance(
            note(listOf(listOf("e", parent, "", "reply"))), own, ownEventIds = setOf(parent),
        ))
    }

    @Test fun quoteOfOwnArticleIsRelevant() {
        val address = "30023:$own:article"
        assertEquals(NotificationRelevance.Relevant,
            notificationRelevance(note(listOf(listOf("q", address, "wss://relay"))), own))
        assertEquals(NotificationRelevance.Relevant,
            notificationRelevance(note(emptyList(), "読んだ nostr:${encodeNaddr(30023, own, "article")}"), own))
        assertEquals(NotificationRelevance.Unrelated,
            notificationRelevance(note(listOf(listOf("q", "30023:$other:article"), listOf("p", own))), own))
        assertEquals(NotificationRelevance.Unrelated,
            notificationRelevance(note(listOf(listOf("p", own)), "nostr:${encodeNaddr(30023, other, "article")}"), own))
    }

    @Test fun replyToOwnArticleIsRelevant() {
        val reply = note(listOf(listOf("a", "30023:$own:article"), listOf("p", own)))
        assertEquals(NotificationRelevance.Relevant, notificationRelevance(reply, own))
    }

    @Test fun otherKindsAreNotFiltered() {
        assertEquals(NotificationRelevance.Relevant, notificationRelevance(note(listOf(listOf("p", own)), kind = 7), own))
    }

    @Test fun decidesByFetchedTargetAuthor() {
        fun event(pubkey: String) = NostrEvent(parent, pubkey, 1, 1, emptyList(), "", "0".repeat(128))
        val ids = setOf(parent, quoted)
        assertNull(decideByTargetAuthors(ids, mapOf(parent to TargetLoadState.Resolved(event(other))), own))
        assertEquals(true, decideByTargetAuthors(ids, mapOf(parent to TargetLoadState.Resolved(event(own))), own))
        assertEquals(false, decideByTargetAuthors(ids, mapOf(
            parent to TargetLoadState.Resolved(event(other)), quoted to TargetLoadState.Resolved(event(actor)),
        ), own))
        // 取れなかったものは自分の投稿だったかもしれないので出す。
        assertEquals(true, decideByTargetAuthors(ids, mapOf(
            parent to TargetLoadState.Resolved(event(other)), quoted to TargetLoadState.NotFoundInQueriedRelays,
        ), own))
        assertEquals(true, decideByTargetAuthors(setOf(parent), mapOf(
            parent to TargetLoadState.Unavailable(TargetFetchFailure.Incomplete),
        ), own))
    }
}
