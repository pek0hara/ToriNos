package com.nostr.torinos.chat

import com.nostr.torinos.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatThreadCacheTest {
    private val relayA = "wss://a.example"
    private val relayB = "wss://b.example"

    private fun event(id: String, createdAt: Long, content: String = "body $id", kind: Int = GROUP_CHAT_KIND) =
        NostrEvent(id = id, pubkey = "pk-$id", createdAt = createdAt, kind = kind, tags = emptyList(), content = content, sig = "")

    private fun message(event: NostrEvent, author: String = event.pubkey, body: String = event.content) =
        GroupMessage(event, author, body, null, null, emptyList())

    private fun entry(relay: String, rootId: String, lastActivity: Long, reply: Boolean = true, rootKind: Int = GROUP_CHAT_KIND) =
        JoinedTimelineEntry(
            GroupRef(relay, "g"),
            GroupConversation(
                rootId = rootId,
                root = message(event(rootId, lastActivity - 10, kind = rootKind)),
                latestReply = if (reply) message(event("$rootId-r", lastActivity), author = "actor") else null,
                replyCount = if (reply) 3 else 0,
                lastActivity = lastActivity,
                involvesMe = true,
            ),
        )

    @Test
    fun roundTripKeepsWhatTheRowNeeds() {
        val original = entry(relayA, "root1", 100)
        val stored = ChatThreadCache.encode(listOf(CachedThread.of(original, GroupFlavor.Buzz)))
        val restored = ChatThreadCache.decode(stored).single()
        assertEquals(GroupFlavor.Buzz, restored.flavor)
        val back = restored.toEntry()
        assertEquals(original.key, back.key)
        assertEquals(100, back.conversation.lastActivity)
        assertEquals(3, back.conversation.replyCount)
        assertEquals("actor", back.conversation.latestReply?.author)
        assertEquals("body root1", back.conversation.root?.body)
        assertTrue(back.opensThread)
        assertTrue(back.isMyThread)
    }

    @Test
    fun chatPostWithoutRepliesStillOpensTheGroup() {
        val back = CachedThread.of(entry(relayA, "solo", 50, reply = false), GroupFlavor.Standard).toEntry()
        assertNull(back.conversation.latestReply)
        assertFalse(back.opensThread)
        val forum = CachedThread.of(entry(relayA, "forum", 50, reply = false, rootKind = BUZZ_FORUM_POST_KIND), GroupFlavor.Buzz).toEntry()
        assertTrue(forum.opensThread)
    }

    @Test
    fun trimsBodiesAndCount() {
        val long = "あ".repeat(2_000)
        val big = JoinedTimelineEntry(
            GroupRef(relayA, "g"),
            GroupConversation("r", message(event("r", 1, content = long)), null, 0, 1, involvesMe = true),
        )
        val many = (1..150).map { entry(relayA, "t$it", it.toLong()) } + big
        val restored = ChatThreadCache.decode(ChatThreadCache.encode(many.map { CachedThread.of(it, GroupFlavor.Standard) }))
        assertEquals(THREAD_CACHE_MAX, restored.size)
        assertEquals(150, restored.first().lastActivity)
        val trimmed = ChatThreadCache.decode(ChatThreadCache.encode(listOf(CachedThread.of(big, GroupFlavor.Standard)))).single()
        assertEquals(THREAD_CACHE_BODY_MAX, trimmed.root?.body?.length)
        assertEquals(THREAD_CACHE_BODY_MAX, trimmed.root?.event?.content?.length)
    }

    @Test
    fun brokenStorageIsEmpty() {
        assertEquals(emptyList(), ChatThreadCache.decode(null))
        assertEquals(emptyList(), ChatThreadCache.decode("{not json"))
    }

    @Test
    fun mergeReplacesFreshRelaysAndKeepsOthers() {
        val fresh = listOf(entry(relayA, "a2", 200))
        val previous = listOf(entry(relayA, "a1", 300), entry(relayB, "b1", 250), entry(relayB, "a2-dup", 10))
        val merged = mergeThreadEntries(fresh, previous, keep = { it == relayB })
        // relayA は今回の結果だけ（a1 は消えた）、relayB は前回のまま。新しい順。
        assertEquals(listOf("b1", "a2", "a2-dup"), merged.map { it.conversation.rootId })
    }

    @Test
    fun mergePrefersFreshForTheSameThread() {
        val fresh = listOf(entry(relayB, "same", 400))
        val previous = listOf(entry(relayB, "same", 100))
        val merged = mergeThreadEntries(fresh, previous, keep = { true })
        assertEquals(listOf(400L), merged.map { it.conversation.lastActivity })
    }

    private fun cached(relay: String, rootId: String, lastActivity: Long) = CachedThread.of(entry(relay, rootId, lastActivity), GroupFlavor.Standard)

    @Test
    fun restoresOnlyTheSelectedRelayAndSkipsMuted() {
        val threads = listOf(cached(relayA, "a1", 10), cached(relayB, "b1", 20), cached(relayA, "a2", 30))
        assertEquals(listOf("a2", "a1"), restorableThreads(threads, relayFilter = relayA, muted = emptySet()).map { it.rootId })
        assertEquals(listOf("a2", "b1", "a1"), restorableThreads(threads, relayFilter = null, muted = emptySet()).map { it.rootId })
        // 最新の返信の作者（actor）か、ルートの作者をミュートしていれば出さない。
        assertEquals(emptyList(), restorableThreads(threads, relayFilter = null, muted = setOf("actor")).map { it.rootId })
        assertEquals(listOf("a2", "b1"), restorableThreads(threads, relayFilter = null, muted = setOf("pk-a1")).map { it.rootId })
    }

    @Test
    fun savingReplacesAnsweredRelaysAndKeepsTheRest() {
        val previous = listOf(cached(relayA, "old-a", 300), cached(relayB, "b1", 200))
        val fresh = listOf(cached(relayA, "new-a", 100))
        val merged = mergeCachedThreads(fresh, previous, keep = { it != relayA })
        assertEquals(listOf("b1", "new-a"), merged.map { it.rootId })
    }
}
