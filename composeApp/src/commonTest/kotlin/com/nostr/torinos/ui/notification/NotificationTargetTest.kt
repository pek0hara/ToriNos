package com.nostr.torinos.ui.notification

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.network.TargetLoadState
import com.nostr.torinos.network.TargetFetchFailure
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationTargetTest {
    private val id = "a".repeat(64)
    private val parent = "b".repeat(64)
    private fun event(kind: Int = 7, tags: List<List<String>> = listOf(listOf("e", id)), content: String = "body") =
        NostrEvent(id, "c".repeat(64), 123, kind, tags, content, "d".repeat(128))

    @Test fun lastETagWinsRegardlessOfKindHintAndMalformedLastTagDoesNotFallBack() {
        assertEquals(TargetReference.EventId(id), resolveNotificationTarget(event(tags = listOf(
            listOf("e", parent), listOf("e", id), listOf("k", "42"), listOf("a", "30023:author:article"),
        ))).reference)
        assertEquals(TargetReference.Invalid, resolveNotificationTarget(event(tags = listOf(listOf("e", id), listOf("e", "bad")))).reference)
        assertEquals(TargetReference.Invalid, resolveNotificationTarget(event(tags = listOf(listOf("e")))).reference)
    }

    @Test fun replyUsesReplyMarkerNotLastETag() {
        val reply = event(1, listOf(listOf("e", parent, "", "reply"), listOf("e", id, "", "mention")))
        assertEquals(TargetReference.EventId(parent), resolveNotificationTarget(reply).reference)
    }

    @Test fun absentAndAddressOnlyReferencesDoNotStartIdLookup() {
        assertEquals(TargetReference.None, resolveNotificationTarget(event(tags = emptyList())).reference)
        assertEquals(TargetReference.AddressOnly, resolveNotificationTarget(event(tags = listOf(listOf("a", "30023:author:article")))).reference)
        assertEquals(TargetReference.EventId(id), resolveNotificationTarget(null, id).reference)
        assertEquals(TargetReference.Invalid, resolveNotificationTarget(null, "bad").reference)
        // Do not resurrect stale saved IDs when an actual event says otherwise.
        assertEquals(TargetReference.None, resolveNotificationTarget(event(tags = emptyList()), id).reference)
    }

    @Test fun embeddedRepostMustBeValidAndMatchExplicitId() {
        val embedded = event(42)
        val repost = event(6, content = Json.encodeToString(embedded))
        assertEquals(embedded, resolveNotificationTarget(repost, validateEmbedded = { true }).embedded)
        assertNull(resolveNotificationTarget(repost, validateEmbedded = { false }).embedded)
        assertNull(resolveNotificationTarget(repost.copy(tags = listOf(listOf("e", parent))), validateEmbedded = { true }).embedded)
        val noTag = repost.copy(tags = emptyList())
        assertEquals(TargetReference.EventId(id), resolveNotificationTarget(noTag, validateEmbedded = { true }).reference)
        assertEquals(TargetReference.Invalid, resolveNotificationTarget(noTag, validateEmbedded = { false }).reference)
    }

    @Test fun destinationsPreserveChannelContextAndNeverTreatUnknownKindsAsNotes() {
        assertEquals(NotificationTargetDestination.Thread(id), notificationTargetDestination(event(1)))
        assertEquals(NotificationTargetDestination.ChannelThread(id, parent), notificationTargetDestination(
            event(42, listOf(listOf("e", parent, "", "root"), listOf("e", id, "", "reply"))),
        ))
        assertNull(notificationTargetDestination(event(42, emptyList())))
        assertNull(notificationTargetDestination(event(42, listOf(listOf("e", "bad")))))
        assertEquals(NotificationTargetDestination.Article("c".repeat(64), "article"), notificationTargetDestination(event(30023, listOf(listOf("d", "article")))))
        assertEquals(NotificationTargetDestination.Live("c".repeat(64), "live"), notificationTargetDestination(event(30311, listOf(listOf("d", "live")))))
        listOf(30023, 30311, 1311, 30315, 99999).forEach { kind ->
            assertNull(notificationTargetDestination(event(kind, emptyList())))
        }
    }

    @Test fun unknownContentIsNotRenderedAndFailuresNeverSayLoading() {
        listOf(1311, 30315, 99999).forEach { kind ->
            val body = notificationTargetBody(event(kind, content = "secret ciphertext"))
            assertFalse(body.contains("secret"))
            assertTrue(body.contains(kind.toString()))
        }
        val reference = TargetReference.EventId(id)
        assertEquals("対象を読み込み中", targetStatusText(reference, TargetLoadState.Loading))
        assertFalse(targetStatusText(reference, TargetLoadState.NotFoundInQueriedRelays).contains("読み込み中"))
        assertFalse(targetStatusText(reference, TargetLoadState.Unavailable(TargetFetchFailure.Incomplete)).contains("読み込み中"))
        assertEquals("この参照形式は未対応", targetStatusText(TargetReference.AddressOnly, null))
    }

    @Test fun savedNotificationsRemainCompatibleAndReferenceIsRebuilt() {
        val item = NotificationItem(id, NotificationType.Like, "actor", 123, 123, id, event(), TargetReference.EventId(id))
        val json = Json.encodeToString(item)
        assertFalse(json.contains("targetReference"))
        val decoded = Json.decodeFromString<NotificationItem>(json)
        assertEquals(id, decoded.targetEventId)
        assertIs<TargetReference.EventId>(resolveNotificationTarget(decoded.event, decoded.targetEventId).reference)
    }
}
