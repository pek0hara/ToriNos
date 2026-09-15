package com.nostr.torinos.network

import com.nostr.torinos.model.COMMENT_EVENT_KIND
import com.nostr.torinos.model.NoteContext
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.ReplyTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class ReplyTargetResolverTest {
    @Test
    fun missingRootMetadataIsRecoveredFromRootEvent() = runTest {
        val rootId = "a".repeat(64)
        val parent = event(
            id = "b".repeat(64),
            pubkey = "comment-author",
            kind = COMMENT_EVENT_KIND,
            tags = listOf(listOf("E", rootId, "wss://root.example")),
        )
        val root = event(id = rootId, pubkey = "root-author", kind = 1)
        val fetcher = ReplyRootEventFetcher { id, relayHint ->
            assertEquals(rootId, id)
            assertEquals("wss://root.example", relayHint)
            root
        }

        val target = resolveReplyTarget(parent, NoteContext.Timeline, fetcher) as ReplyTarget.Timeline

        assertEquals(rootId, target.root.id)
        assertEquals(1, target.root.kind)
        assertEquals("root-author", target.root.pubkey)
        assertEquals("wss://root.example", target.root.relayUrl)
        assertEquals(COMMENT_EVENT_KIND, target.parent.kind)
    }

    @Test
    fun contradictoryDeclaredRootIsCorrectedFromFetchedEvent() = runTest {
        val rootId = "a".repeat(64)
        val parent = event(
            id = "b".repeat(64),
            pubkey = "comment-author",
            kind = COMMENT_EVENT_KIND,
            tags = listOf(
                listOf("E", rootId, "", "wrong-author"),
                listOf("K", "42"),
                listOf("P", "wrong-author", "wss://wrong-author.example"),
            ),
        )
        val root = event(id = rootId, pubkey = "root-author", kind = 1)

        val target = resolveReplyTarget(
            parent,
            NoteContext.Timeline,
            ReplyRootEventFetcher { _, _ -> root },
        ) as ReplyTarget.Timeline

        assertEquals(1, target.root.kind)
        assertEquals("root-author", target.root.pubkey)
        assertEquals(null, target.root.pubkeyRelayUrl)
    }

    @Test
    fun legacyKind1ReplyRecoversMissingRootAuthor() = runTest {
        val rootId = "a".repeat(64)
        val parent = event(
            id = "b".repeat(64),
            pubkey = "reply-author",
            kind = 1,
            tags = listOf(listOf("e", rootId, "wss://root.example")),
        )
        val root = event(id = rootId, pubkey = "root-author", kind = 1)

        val target = resolveReplyTarget(
            parent,
            NoteContext.Timeline,
            ReplyRootEventFetcher { id, relayHint ->
                assertEquals(rootId, id)
                assertEquals("wss://root.example", relayHint)
                root
            },
        ) as ReplyTarget.Timeline

        assertEquals(rootId, target.root.id)
        assertEquals("root-author", target.root.pubkey)
        assertEquals(parent.id, target.parent.id)
        assertEquals(1, target.parent.kind)
    }

    private fun event(
        id: String,
        pubkey: String,
        kind: Int,
        tags: List<List<String>> = emptyList(),
    ) = NostrEvent(id, pubkey, 0L, kind, tags, "content", "sig")
}
