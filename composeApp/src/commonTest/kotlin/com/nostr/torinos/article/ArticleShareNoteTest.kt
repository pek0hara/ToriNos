package com.nostr.torinos.article

import com.nostr.torinos.model.encodeNaddr
import kotlin.test.Test
import kotlin.test.assertEquals

class ArticleShareNoteTest {
    private val pubkey = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
    private val naddr = encodeNaddr(30023, pubkey, "torinos-abc", listOf("wss://yabu.me"))

    @Test
    fun commentComesFirstThenTheArticleReference() {
        val note = buildArticleShareNote(" 書きました ", "タイトル", pubkey, "torinos-abc", "wss://yabu.me")

        assertEquals("書きました\n\nnostr:$naddr", note.content)
        assertEquals(
            listOf(
                listOf("q", "30023:$pubkey:torinos-abc", "wss://yabu.me"),
                listOf("client", "ToriNos"),
            ),
            note.tags,
        )
    }

    @Test
    fun withoutACommentTheTitleLeads() {
        val note = buildArticleShareNote("", "タイトル", pubkey, "torinos-abc", "wss://yabu.me")
        assertEquals("タイトル\n\nnostr:$naddr", note.content)
    }

    @Test
    fun withoutCommentOrTitleOnlyTheReferenceRemains() {
        val note = buildArticleShareNote("", " ", pubkey, "torinos-abc", null)
        assertEquals("nostr:" + encodeNaddr(30023, pubkey, "torinos-abc"), note.content)
        assertEquals(listOf("q", "30023:$pubkey:torinos-abc", ""), note.tags.first())
    }
}
