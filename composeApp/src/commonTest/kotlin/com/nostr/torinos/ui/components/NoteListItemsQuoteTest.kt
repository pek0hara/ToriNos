package com.nostr.torinos.ui.components

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NoteListItemsQuoteTest {
    private fun event(id: Char, pubkey: Char = 'a', tags: List<List<String>> = emptyList()) =
        NostrEvent(id.toString().repeat(64), pubkey.toString().repeat(64), 1, 1, tags, "", "")

    private val parent = event('1', pubkey = 'p')
    private val quoted = event('2', pubkey = 'q')
    private val missing = '3'.toString().repeat(64)
    private val profiles = mapOf(parent.pubkey to NostrProfile(name = "parent"), quoted.pubkey to NostrProfile(name = "quoted"))
    private val fetched = mapOf(parent.id to parent, quoted.id to quoted)

    @Test fun replyParentUsesFetchedTargetAndItsProfile() {
        val reply = event('r', tags = listOf(listOf("e", parent.id, "", "reply")))
        assertEquals(QuotedEvent(parent, profiles[parent.pubkey]), noteReplyParent(reply, fetched, profiles))
        assertNull(noteReplyParent(reply, emptyMap(), profiles))
        assertNull(noteReplyParent(event('n'), fetched, profiles))
    }

    @Test fun quotedEventsKeepOrderSkipUnfetchedAndExcludeReplyTarget() {
        val note = event('r', tags = listOf(
            listOf("e", parent.id, "", "reply"),
            listOf("q", parent.id),
            listOf("q", missing),
            listOf("q", quoted.id),
        ))
        assertEquals(listOf(QuotedEvent(quoted, profiles[quoted.pubkey])), noteQuotedEvents(note, fetched, profiles))
    }

    @Test fun equalInputsBuildEqualValuesSoRememberKeepsTheFirstInstance() {
        // The item remembers by these values; equality is what lets it reuse the previous instance.
        val note = event('r', tags = listOf(listOf("q", quoted.id)))
        assertEquals(noteQuotedEvents(note, fetched, profiles), noteQuotedEvents(note, fetched.toMap(), profiles.toMap()))
    }
}
