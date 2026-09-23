package com.nostr.torinos.ui.feed

import com.nostr.torinos.crypto.Bech32
import com.nostr.torinos.crypto.toHex
import com.nostr.torinos.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class MentionedPubkeysCacheTest {
    private val mentionedPubkey = ByteArray(32) { 1 }.toHex()
    private val mentionedNpub = Bech32.encode("npub", ByteArray(32) { 1 })

    @Test
    fun resolvesNpubMentionsInContent() {
        val cache = MentionedPubkeysCache()
        val event = event("note1", "hello $mentionedNpub")

        assertEquals(setOf(mentionedPubkey), cache.mentionedPubkeys(event))
    }

    @Test
    fun reusesCachedResultForSameEventId() {
        val cache = MentionedPubkeysCache()
        val event = event("note1", "hello $mentionedNpub")

        val first = cache.mentionedPubkeys(event)
        val second = cache.mentionedPubkeys(event)

        assertSame(first, second)
    }

    @Test
    fun evictsOldestEntryBeyondMaxEntries() {
        val cache = MentionedPubkeysCache(maxEntries = 2)
        cache.mentionedPubkeys(event("note1", "no mention"))
        cache.mentionedPubkeys(event("note2", "no mention"))
        cache.mentionedPubkeys(event("note3", "no mention"))

        // note1 should have been evicted (capacity 2). If eviction were broken, this
        // would incorrectly return the stale cached "no mention" result instead of
        // re-parsing the new content.
        assertEquals(setOf(mentionedPubkey), cache.mentionedPubkeys(event("note1", "hello $mentionedNpub")))
    }

    private fun event(id: String, content: String) =
        NostrEvent(id, "author", 0, 1, emptyList(), content, "sig")
}
