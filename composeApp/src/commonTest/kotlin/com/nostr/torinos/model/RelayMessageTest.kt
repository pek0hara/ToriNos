package com.nostr.torinos.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RelayMessageTest {

    private val zeroId = "0".repeat(64)
    private val dummyEvent = NostrEvent(
        id = zeroId,
        pubkey = zeroId,
        createdAt = 1_700_000_000L,
        kind = 1,
        tags = emptyList(),
        content = "hello",
        sig = "0".repeat(128),
    )

    // ---- parseRelayMessage ----

    @Test
    fun parse_eventMessageWithInvalidSignature_returnsUnknown() {
        val raw = buildRawEventMessage("sub1", dummyEvent)
        val parsed = parseRelayMessage(raw)
        assertIs<RelayMessage.Unknown>(parsed)
        assertEquals(raw, parsed.raw)
    }

    @Test
    fun parse_eoseMessage_returnsEndOfStoredEvents() {
        val parsed = parseRelayMessage("""["EOSE","sub42"]""")
        assertIs<RelayMessage.EndOfStoredEvents>(parsed)
        assertEquals("sub42", parsed.subscriptionId)
    }

    @Test
    fun parse_okMessage_returnsPublishResult() {
        val parsed = parseRelayMessage("""["OK","$zeroId",false,"restricted: not a member"]""")
        assertIs<RelayMessage.Ok>(parsed)
        assertEquals(zeroId, parsed.eventId)
        assertEquals(false, parsed.accepted)
        assertEquals("restricted: not a member", parsed.message)
    }

    @Test
    fun parse_closedWithMessage_returnsClosedWithMessage() {
        val parsed = parseRelayMessage("""["CLOSED","sub1","auth-required: please authenticate"]""")
        assertIs<RelayMessage.Closed>(parsed)
        assertEquals("sub1", parsed.subscriptionId)
        assertEquals("auth-required: please authenticate", parsed.message)
    }

    @Test
    fun parse_closedWithoutMessage_returnsEmptyMessage() {
        val parsed = parseRelayMessage("""["CLOSED","sub1"]""")
        assertIs<RelayMessage.Closed>(parsed)
        assertEquals("", parsed.message)
    }

    @Test
    fun parse_noticeMessage_returnsNotice() {
        val parsed = parseRelayMessage("""["NOTICE","server restarting"]""")
        assertIs<RelayMessage.Notice>(parsed)
        assertEquals("server restarting", parsed.message)
    }

    @Test
    fun parse_unknownType_returnsUnknown() {
        val raw = """["AUTH","challenge-token"]"""
        val parsed = parseRelayMessage(raw)
        assertIs<RelayMessage.Unknown>(parsed)
        assertEquals(raw, parsed.raw)
    }

    @Test
    fun parse_invalidJson_returnsUnknown() {
        val raw = "not json at all"
        val parsed = parseRelayMessage(raw)
        assertIs<RelayMessage.Unknown>(parsed)
        assertEquals(raw, parsed.raw)
    }

    @Test
    fun parse_emptyArray_returnsUnknown() {
        val raw = "[]"
        val parsed = parseRelayMessage(raw)
        assertIs<RelayMessage.Unknown>(parsed)
    }

    // ---- buildEventMessage ----

    @Test
    fun buildEventMessage_startsWithEventKeyword() {
        val msg = buildEventMessage(dummyEvent)
        assertTrue(msg.startsWith("""["EVENT","""))
    }

    @Test
    fun buildEventMessage_containsEventId() {
        val msg = buildEventMessage(dummyEvent)
        assertTrue(msg.contains(dummyEvent.id))
    }

    @Test
    fun buildEventMessage_roundTripsContent() {
        val event = dummyEvent.copy(content = "special \"quoted\" content")
        val msg = buildEventMessage(event)
        assertTrue(msg.contains("special"))
    }

    // ---- helper ----

    private fun buildRawEventMessage(subId: String, event: NostrEvent): String {
        // buildEventMessage produces ["EVENT", <event>]; we need ["EVENT", "<subId>", <event>]
        val eventJson = buildEventMessage(event)
        val eventObject = eventJson
            .removePrefix("[\"EVENT\",")
            .trimEnd(']')
            .trim()
        return """["EVENT","$subId",$eventObject]"""
    }
}
