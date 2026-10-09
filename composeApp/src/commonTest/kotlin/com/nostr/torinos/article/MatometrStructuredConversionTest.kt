package com.nostr.torinos.article

import kotlin.test.*

class MatometrStructuredConversionTest {
    private val first = MatometrReference("11".repeat(32), "aa".repeat(32), listOf("wss://relay.example"))
    private val second = MatometrReference("22".repeat(32), "bb".repeat(32))
    private val third = MatometrReference("33".repeat(32))
    private fun free(body: String) = ArticleDraft(ownerPubkey = "aa".repeat(32), mode = ArticleDraftMode.MatometrMarkdown, bodyMarkdown = body)
    private fun converted(body: String) = assertIs<StructuredConversion.Ok>(toStructuredMatometr(free(body))).draft
    private fun blocked(body: String) = assertIs<StructuredConversion.Blocked>(toStructuredMatometr(free(body))).reason

    @Test fun cardsSwitchedToFreeMarkdownComeBackUnchanged() {
        val structured = ArticleDraft(ownerPubkey = "aa".repeat(32), mode = ArticleDraftMode.MatometrStructured,
            bodyMarkdown = "# 導入\n\n本文", references = listOf(
                first.copy(commentaryMarkdown = "一番の説明\n\n```\ncode\n\n\nblock\n```"),
                second, third.copy(commentaryMarkdown = "- 箇条書き\n- 二つ目")))
        // The same transformation useFreeMarkdown applies.
        val back = converted(structured.content)
        assertEquals(ArticleDraftMode.MatometrStructured, back.mode)
        assertEquals(structured.bodyMarkdown, back.bodyMarkdown)
        assertEquals(structured.references, back.references)
        assertEquals(structured.content, back.content)
    }

    @Test fun referenceLinesWithoutBlankLinesAndClosingTextAreAccepted() {
        val back = converted("導入\n${first.uri}\n${second.uri}\n\n\n\nまとめ\n\n#matometr\n")
        assertEquals("導入", back.bodyMarkdown)
        assertEquals(listOf(first.eventId, second.eventId), back.references.map { it.eventId })
        assertEquals(listOf("", "まとめ\n\n#matometr"), back.references.map { it.commentaryMarkdown })
        assertEquals(first.relayHints, back.references[0].relayHints)
        assertEquals(first.authorPubkey, back.references[0].authorPubkey)
    }

    @Test fun referencesWithoutTheNostrPrefixOrAsNoteAreCards() {
        val note = com.nostr.torinos.crypto.Bech32.encode("note", third.eventId.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        val back = converted("${first.uri.removePrefix("nostr:")}\n\nnostr:$note")
        assertEquals(listOf(first.eventId, third.eventId), back.references.map { it.eventId })
        assertEquals("", back.bodyMarkdown)
    }

    @Test fun referencesACardCannotHoldBlockTheConversion() {
        assertTrue("2行目" in blocked("導入\nこれは ${first.uri} の話"))
        assertTrue("2行目" in blocked("導入\n- ${first.uri}"))
        assertTrue("3行目" in blocked("${first.uri}\n\n> ${second.uri}"))
        assertTrue("同じ投稿" in blocked("${first.uri}\n\n説明\n\n${first.uri}"))
        assertEquals("参照だけの行に置かれた投稿がありません", blocked("本文だけ"))
        assertEquals("コードブロックを閉じてください", blocked("${first.uri}\n\n```\n"))
    }

    @Test fun referencesInsideCodeStayText() {
        val back = converted("${first.uri}\n\n```\n${second.uri}\n```\n\n    ${third.uri}")
        assertEquals(listOf(first.eventId), back.references.map { it.eventId })
        assertEquals("```\n${second.uri}\n```\n\n    ${third.uri}", back.references.single().commentaryMarkdown)
    }
}
