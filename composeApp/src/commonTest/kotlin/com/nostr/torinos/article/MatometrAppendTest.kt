package com.nostr.torinos.article

import com.nostr.torinos.model.encodeNevent
import kotlin.test.*

class MatometrAppendTest {
    private val first = MatometrReference("11".repeat(32), "aa".repeat(32), listOf("wss://relay.example"))
    private val second = MatometrReference("22".repeat(32), "bb".repeat(32))
    private val note = com.nostr.torinos.crypto.Bech32.encode("note", ByteArray(32) { 0x33 })
    private fun draft(mode: ArticleDraftMode, body: String = "", refs: List<MatometrReference> = emptyList()) =
        ArticleDraft(ownerPubkey = "aa".repeat(32), mode = mode, bodyMarkdown = body, references = refs)

    @Test fun pastedTextYieldsPostsFromUrlsAndReferencesButNotArticlesOrProfiles() {
        val nevent = encodeNevent(first.eventId, first.relayHints, first.authorPubkey)
        val npub = com.nostr.torinos.crypto.Bech32.encode("npub", ByteArray(32) { 0x44 })
        val parsed = parsePastedEventReferences("見て https://njump.me/$nevent\nnostr:$note と $note\n$npub")
        assertEquals(listOf(first.eventId, "33".repeat(32)), parsed.references.map { it.eventId })
        assertEquals(first.relayHints, parsed.references.first().relayUrls)
        assertEquals(first.authorPubkey, parsed.references.first().authorPubkey)
        assertEquals(1, parsed.unsupported)
    }

    @Test fun aBareHexIdIsAcceptedOnlyAsTheWholeInput() {
        assertEquals(listOf("ab".repeat(32)), parsePastedEventReferences("  ${"AB".repeat(32)}\n").references.map { it.eventId })
        assertTrue(parsePastedEventReferences("hash: ${"ab".repeat(32)}").references.isEmpty())
    }

    @Test fun cardsAreAppendedWithoutDuplicates() {
        val result = appendMatometrReferences(draft(ArticleDraftMode.MatometrStructured, refs = listOf(first)), listOf(first, second)).getOrThrow()
        assertEquals(listOf(first.eventId, second.eventId), result.references.map { it.eventId })
    }

    @Test fun freeMarkdownAppendsReferencesAndPreservesWrittenHashtags() {
        val body = "導入\n\n${first.uri}\n\n#matometr"
        val result = appendMatometrReferences(draft(ArticleDraftMode.MatometrMarkdown, body), listOf(first, second)).getOrThrow()
        assertEquals("$body\n\n${second.uri}", result.bodyMarkdown)
        assertEquals(result.bodyMarkdown, result.content)
        assertEquals("コードブロックを閉じてください",
            appendMatometrReferences(draft(ArticleDraftMode.MatometrMarkdown, "${first.uri}\n\n```"), listOf(second)).exceptionOrNull()?.message)
    }

    @Test fun nothingIsAddedPastTheLimits() {
        val refs = (1..49).map { MatometrReference(it.toString(16).padStart(64, '0')) }
        val full = draft(ArticleDraftMode.MatometrStructured, refs = refs)
        assertEquals("あと1件まで追加できます", appendMatometrReferences(full, listOf(first, second)).exceptionOrNull()?.message)
        assertEquals(50, appendMatometrReferences(full, listOf(first)).getOrThrow().references.size)
        assertEquals("まとめる投稿は50件までです",
            appendMatometrReferences(full.copy(references = refs + first), listOf(second)).exceptionOrNull()?.message)
        val long = draft(ArticleDraftMode.MatometrStructured, "あ".repeat(9_900), listOf(first))
        assertEquals("追加すると本文が10,000文字を超えます", appendMatometrReferences(long, listOf(second)).exceptionOrNull()?.message)
    }
}
