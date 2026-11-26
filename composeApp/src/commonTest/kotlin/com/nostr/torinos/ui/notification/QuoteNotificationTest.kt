package com.nostr.torinos.ui.notification

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.encodeNaddr
import com.nostr.torinos.model.encodeNevent
import kotlin.test.Test
import kotlin.test.assertEquals

class QuoteNotificationTest {
    private val own = "1".repeat(64)
    private val quoted = "a".repeat(64)
    private val parent = "b".repeat(64)

    private fun note(tags: List<List<String>>, content: String = "hi", kind: Int = 1) =
        NostrEvent("f".repeat(64), "3".repeat(64), 1, kind, tags, content, "0".repeat(128))

    @Test fun quoteWithoutReplyIsAQuote() {
        assertEquals(NotificationType.Quote, notificationTypeForNote(note(listOf(listOf("q", quoted)))))
        assertEquals(NotificationType.Quote, notificationTypeForNote(note(emptyList(), "👀 nostr:${encodeNevent(quoted)}")))
        assertEquals(NotificationType.Quote, notificationTypeForNote(note(listOf(listOf("e", quoted, "", "mention")))))
    }

    @Test fun replyStaysAReplyEvenWhenItQuotes() {
        assertEquals(NotificationType.Reply, notificationTypeForNote(note(listOf(listOf("e", parent, "", "reply"), listOf("q", quoted)))))
        assertEquals(NotificationType.Reply, notificationTypeForNote(note(listOf(listOf("p", own)), "nostr:npub")))
    }

    @Test fun quoteTargetsTheQuotedNote() {
        val byTag = resolveNotificationTarget(note(listOf(listOf("q", quoted, "wss://relay"), listOf("p", own))))
        assertEquals(TargetReference.EventId(quoted), byTag.reference)
        val byContent = resolveNotificationTarget(note(emptyList(), "👀\nnostr:${encodeNevent(quoted, authorPubkey = own)}"))
        assertEquals(TargetReference.EventId(quoted), byContent.reference)
    }

    @Test fun articleQuoteHasAnAddressTarget() {
        val byTag = resolveNotificationTarget(note(listOf(listOf("q", "30023:$own:article"))))
        assertEquals(TargetReference.AddressOnly, byTag.reference)
        val byContent = resolveNotificationTarget(note(emptyList(), "nostr:${encodeNaddr(30023, own, "article")}"))
        assertEquals(TargetReference.AddressOnly, byContent.reference)
    }

    @Test fun grouping_keepsQuotesSeparate() {
        val items = listOf('c', 'd').map { id ->
            NotificationItem(id.toString().repeat(64), NotificationType.Quote, "3".repeat(64), 10, 10, quoted, null)
        }
        val groups = groupNotifications(items).filterIsInstance<NotificationListEntry.Group>()
        assertEquals(2, groups.size)
    }
}
