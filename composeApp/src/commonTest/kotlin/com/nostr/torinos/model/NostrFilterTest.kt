package com.nostr.torinos.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NostrFilterTest {

    // ---- buildReqMessage ----

    @Test
    fun buildReqMessage_format() {
        val filter = NostrFilter(kinds = listOf(1))
        val msg = buildReqMessage("sub1", filter)
        assertTrue(msg.startsWith("""["REQ","sub1","""))
        assertTrue(msg.endsWith("]"))
    }

    @Test
    fun buildReqMessage_containsKinds() {
        val msg = buildReqMessage("s", NostrFilter(kinds = listOf(1, 3)))
        assertTrue(msg.contains(""""kinds":[1,3]"""))
    }

    @Test
    fun buildReqMessage_containsAuthors() {
        val authors = listOf("aabb", "ccdd")
        val msg = buildReqMessage("s", NostrFilter(authors = authors))
        assertTrue(msg.contains("aabb"))
        assertTrue(msg.contains("ccdd"))
    }

    @Test
    fun buildReqMessage_nullFieldsOmitted() {
        // encodeDefaults=false means null fields must not appear in output
        val msg = buildReqMessage("s", NostrFilter(kinds = listOf(1)))
        assertFalse(msg.contains("\"ids\""))
        assertFalse(msg.contains("\"authors\""))
        assertFalse(msg.contains("\"since\""))
        assertFalse(msg.contains("\"until\""))
        assertFalse(msg.contains("\"limit\""))
    }

    @Test
    fun buildReqMessage_sinceAndUntil() {
        val msg = buildReqMessage("s", NostrFilter(since = 1000L, until = 2000L))
        assertTrue(msg.contains(""""since":1000"""))
        assertTrue(msg.contains(""""until":2000"""))
    }

    @Test
    fun buildReqMessage_limit() {
        val msg = buildReqMessage("s", NostrFilter(limit = 50))
        assertTrue(msg.contains(""""limit":50"""))
    }

    @Test
    fun buildReqMessage_eTags() {
        val msg = buildReqMessage("s", NostrFilter(eTags = listOf("ev1", "ev2")))
        assertTrue(msg.contains(""""#e":["ev1","ev2"]"""))
    }

    @Test
    fun buildReqMessage_pTags() {
        val msg = buildReqMessage("s", NostrFilter(pTags = listOf("pub1")))
        assertTrue(msg.contains(""""#p":["pub1"]"""))
    }

    @Test
    fun buildReqMessage_tTags() {
        val msg = buildReqMessage("s", NostrFilter(tTags = listOf("bitcoin")))
        assertTrue(msg.contains(""""#t":["bitcoin"]"""))
    }

    @Test
    fun buildReqMessage_search() {
        val msg = buildReqMessage("s", NostrFilter(search = "nostr"))
        assertTrue(msg.contains(""""search":"nostr""""))
    }

    @Test
    fun buildReqMessage_subscriptionIdPreserved() {
        val id = "my-unique-sub-id"
        val msg = buildReqMessage(id, NostrFilter())
        assertTrue(msg.contains(""""$id""""))
    }

    // ---- buildCloseMessage ----

    @Test
    fun buildCloseMessage_format() {
        val msg = buildCloseMessage("sub99")
        assertEquals("""["CLOSE","sub99"]""", msg)
    }

    @Test
    fun buildCloseMessage_preservesSubscriptionId() {
        val id = "abc-123"
        val msg = buildCloseMessage(id)
        assertTrue(msg.contains(id))
    }
}
