package com.nostr.torinos.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReplyTargetTest {
    @Test
    fun kind1ReplyUsesTheSameRootAndParent() {
        val target = event(id = "root", pubkey = "root-author", kind = 1).toTimelineReplyTarget()!!

        assertEquals(COMMENT_EVENT_KIND, target.eventKind)
        assertEquals(
            listOf(
                listOf("E", "root", "", "root-author"),
                listOf("K", "1"),
                listOf("P", "root-author"),
                listOf("e", "root", "", "root-author"),
                listOf("k", "1"),
                listOf("p", "root-author"),
            ),
            target.tags(),
        )
    }

    @Test
    fun nestedCommentKeepsRootAndChangesParentKind() {
        val parent = event(
            id = "comment",
            pubkey = "comment-author",
            kind = COMMENT_EVENT_KIND,
            tags = listOf(
                listOf("E", "root", "wss://root", "root-author"),
                listOf("K", "1"),
                listOf("P", "root-author", "wss://author"),
                listOf("e", "older-comment", "", "older-author"),
                listOf("k", COMMENT_EVENT_KIND.toString()),
                listOf("p", "older-author"),
            ),
        )

        assertEquals(
            listOf(
                listOf("E", "root", "wss://root", "root-author"),
                listOf("K", "1"),
                listOf("P", "root-author", "wss://author"),
                listOf("e", "comment", "", "comment-author"),
                listOf("k", COMMENT_EVENT_KIND.toString()),
                listOf("p", "comment-author"),
            ),
            parent.toTimelineReplyTarget()?.tags(),
        )
    }

    @Test
    fun supportedTimelineCommentRequiresKind1RootAndCompleteAuthorMetadata() {
        val valid = event(
            id = "comment",
            pubkey = "comment-author",
            kind = COMMENT_EVENT_KIND,
            tags = listOf(
                listOf("E", "root", "", "root-author"),
                listOf("K", "1"),
                listOf("P", "root-author"),
                listOf("e", "parent", "", "parent-author"),
                listOf("k", "1"),
                listOf("p", "parent-author"),
            ),
        )

        assertTrue(valid.isSupportedTimelineComment())
        assertFalse(
            valid.copy(tags = valid.tags.map { if (it.firstOrNull() == "K") listOf("K", "30023") else it })
                .isSupportedTimelineComment(),
        )
        assertFalse(
            valid.copy(tags = valid.tags.map { if (it.firstOrNull() == "p") listOf("p", "someone-else") else it })
                .isSupportedTimelineComment(),
        )
        assertFalse(
            valid.copy(tags = valid.tags.map { if (it.firstOrNull() == "P") listOf("P", "someone-else") else it })
                .isSupportedTimelineComment(),
        )
    }

    @Test
    fun timelineCommentWithoutAuthorHintsIsStillAReply() {
        // Ditto・Amethyst などは E/e の4番目や P/p を省くことがある。NIP-22 では作者の手がかりは必須ではない。
        val bare = event(
            kind = COMMENT_EVENT_KIND,
            tags = listOf(
                listOf("E", "root"),
                listOf("K", "1"),
                listOf("e", "parent", "wss://relay.example/"),
                listOf("k", "1111"),
            ),
        )
        assertTrue(bare.isSupportedTimelineComment())
        assertTrue(
            bare.copy(tags = bare.tags + listOf(listOf("P", "root-author"), listOf("p", "parent-author")))
                .isSupportedTimelineComment(),
        )
        assertFalse(bare.copy(tags = bare.tags.filterNot { it.firstOrNull() == "E" }).isSupportedTimelineComment())
        assertFalse(bare.copy(tags = bare.tags.filterNot { it.firstOrNull() == "e" }).isSupportedTimelineComment())
    }

    @Test
    fun commentRootAuthorFallsBackToTheUppercasePTag() {
        val target = event(
            id = "comment",
            pubkey = "comment-author",
            kind = COMMENT_EVENT_KIND,
            tags = listOf(
                listOf("E", "root"),
                listOf("K", "1"),
                listOf("P", "root-author", "wss://p.example/"),
                listOf("e", "parent", "", "parent-author"),
                listOf("k", "1111"),
                listOf("p", "parent-author"),
            ),
        ).toTimelineReplyTarget()!!

        assertEquals("root-author", target.root.pubkey)
        assertEquals("wss://p.example/", target.root.pubkeyRelayUrl)
        assertEquals("comment", target.parent.id)
    }

    @Test
    fun legacyKind1ReplyKeepsItsNip10Root() {
        val parent = event(
            id = "legacy-reply",
            pubkey = "reply-author",
            kind = 1,
            tags = listOf(
                listOf("e", "root", "wss://root.example", "root", "root-author"),
                listOf("e", "older-parent", "", "reply", "older-author"),
            ),
        )

        assertEquals(
            listOf(
                listOf("E", "root", "wss://root.example", "root-author"),
                listOf("K", "1"),
                listOf("P", "root-author"),
                listOf("e", "legacy-reply", "", "reply-author"),
                listOf("k", "1"),
                listOf("p", "reply-author"),
            ),
            parent.toTimelineReplyTarget()?.tags(),
        )
    }

    @Test
    fun legacyPositionalReplyNeedsRootAuthorResolution() {
        assertNull(
            event(
                kind = 1,
                tags = listOf(listOf("e", "root", "wss://root.example")),
            ).toTimelineReplyTarget(),
        )
    }

    @Test
    fun malformedCommentCannotBecomeAReplyTarget() {
        assertNull(event(kind = COMMENT_EVENT_KIND).toTimelineReplyTarget())
        assertNull(
            event(
                kind = COMMENT_EVENT_KIND,
                tags = listOf(listOf("E", "root", "", "author")),
            ).toTimelineReplyTarget(),
        )
        assertNull(
            event(
                kind = COMMENT_EVENT_KIND,
                tags = listOf(
                    listOf("E", "root", "", "root-author"),
                    listOf("K", "1"),
                    listOf("P", "different-author"),
                ),
            ).toTimelineReplyTarget(),
        )
    }

    @Test
    fun channelReplyRemainsKind42() {
        val target = event(id = "message", pubkey = "author", kind = 42)
            .toReplyTarget(NoteContext.Channel("channel"))!!

        assertEquals(42, target.eventKind)
        assertEquals(
            listOf(
                listOf("e", "channel", "", "root"),
                listOf("e", "message", "", "reply"),
                listOf("p", "author"),
            ),
            target.tags(),
        )
    }

    private fun event(
        id: String = "id",
        pubkey: String = "author",
        kind: Int,
        tags: List<List<String>> = emptyList(),
    ) = NostrEvent(
        id = id,
        pubkey = pubkey,
        createdAt = 0L,
        kind = kind,
        tags = tags,
        content = "content",
        sig = "sig",
    )
}
