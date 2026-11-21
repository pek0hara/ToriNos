package com.nostr.torinos.chat

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.matches
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JoinedTimelineTest {
    private val me = "a".repeat(64)
    private val alice = "b".repeat(64)
    private val relayKey = "c".repeat(64)
    private val admin = "d".repeat(64)

    private val buzzRelay = "wss://buzz.example"
    private val stdRelay = "wss://std.example"

    private fun id(n: Int) = n.toString().padStart(64, '0')
    private fun ev(n: Int, group: String, kind: Int = 9, tags: List<List<String>> = emptyList(), pubkey: String = alice, createdAt: Long = n.toLong(), content: String = "m$n") =
        NostrEvent(id(n), pubkey, createdAt, kind, listOf(listOf("h", group)) + tags, content, "sig")

    private fun reply(root: Int) = listOf(listOf("e", id(root), "", "reply"))

    /** リレーごとの保存済みイベントにフィルターを当て、`limit` は新しい順に切る。 */
    private class FakeRelays(val stored: Map<String, List<NostrEvent>>, val failing: Set<String> = emptySet()) {
        val requests = mutableListOf<Pair<String, List<NostrFilter>>>()
        suspend fun fetch(relayUrl: String, filters: List<NostrFilter>): TimelineFetch {
            requests += relayUrl to filters
            if (relayUrl in failing) return TimelineFetch(emptyList(), answered = false)
            val events = filters.flatMap { filter ->
                stored[relayUrl].orEmpty().filter { filter.matches(it) }
                    .sortedByDescending { it.createdAt }
                    .let { list -> filter.limit?.let { list.take(it) } ?: list }
            }.distinctBy { it.id }
            return TimelineFetch(events, answered = true)
        }
    }

    private fun buzzGroup(id: String) = TimelineGroup(GroupRef(buzzRelay, id), GroupFlavor.Buzz, relayKey)
    private fun stdGroup(id: String) = TimelineGroup(GroupRef(stdRelay, id), GroupFlavor.Standard, relayKey)

    @Test
    fun conversationsFromAllGroupsAreMergedByLatestActivity() = runTest {
        val relays = FakeRelays(mapOf(
            buzzRelay to listOf(ev(1, "b1"), ev(2, "b2"), ev(5, "b1", tags = reply(1))),
            stdRelay to listOf(ev(3, "s1"), ev(4, "s1", kind = GROUP_THREAD_KIND)),
        ))
        val loader = JoinedTimelineLoader(me, relays::fetch, pageSize = 10)
        loader.load(listOf(buzzGroup("b1"), buzzGroup("b2"), stdGroup("s1")))

        val entries = loader.entries()
        assertEquals(listOf(id(1), id(4), id(3), id(2)), entries.map { it.conversation.rootId })
        assertEquals(GroupRef(buzzRelay, "b1"), entries.first().ref)
        assertEquals(id(5), entries.first().conversation.latestReply?.event?.id)
    }

    @Test
    fun missingRootsAndOlderRepliesAreReadInTheFollowUp() = runTest {
        // 1ページ（2件）には返信だけが入る。ルートと、ページの外の返信は続きの要求で読む。
        val relays = FakeRelays(mapOf(
            buzzRelay to listOf(ev(1, "b1", content = "root"), ev(2, "b1", tags = reply(1)), ev(8, "b1", tags = reply(1)), ev(9, "b1", tags = reply(1))),
        ))
        val loader = JoinedTimelineLoader(me, relays::fetch, pageSize = 2)
        loader.load(listOf(buzzGroup("b1")))

        val conversation = loader.entries().single().conversation
        assertEquals("root", conversation.root?.body)
        assertEquals(3, conversation.replyCount)
        assertEquals(id(9), conversation.latestReply?.event?.id)
    }

    @Test
    fun deletionsByAuthorsAndAdminsAreApplied() = runTest {
        val relays = FakeRelays(mapOf(
            buzzRelay to listOf(
                ev(1, "b1"), ev(2, "b1"), ev(3, "b1"),
                ev(10, "b1", kind = 5, tags = listOf(listOf("e", id(1)))),
                ev(11, "b1", kind = GROUP_DELETE_EVENT_KIND, tags = listOf(listOf("e", id(2))), pubkey = admin),
                NostrEvent(id(12), relayKey, 1, GROUP_ADMINS_KIND, listOf(listOf("d", "b1"), listOf("p", admin)), "", "sig"),
            ),
        ))
        val loader = JoinedTimelineLoader(me, relays::fetch, pageSize = 10)
        loader.load(listOf(buzzGroup("b1")))

        assertEquals(listOf(id(3)), loader.entries().map { it.conversation.rootId })
    }

    @Test
    fun busyRelayLimitsHowFarBackTheMergedListGoes() = runTest {
        // buzz は1ページで 100..101 までしか読めていない。std の 50 はその間に buzz の投稿が抜けているかもしれないので、まだ出さない。
        val relays = FakeRelays(mapOf(
            buzzRelay to listOf(ev(1, "b1", createdAt = 10), ev(2, "b1", createdAt = 100), ev(3, "b1", createdAt = 101)),
            stdRelay to listOf(ev(4, "s1", createdAt = 50), ev(5, "s1", createdAt = 102)),
        ))
        val loader = JoinedTimelineLoader(me, relays::fetch, pageSize = 2)
        loader.load(listOf(buzzGroup("b1"), stdGroup("s1")))

        assertEquals(listOf(id(5), id(3), id(2)), loader.entries().map { it.conversation.rootId })
        assertTrue(loader.canLoadOlder)

        // 下限を決めていた buzz だけを読み足す。今度は std（50 まで）が下限になる。
        loader.loadOlder()
        assertEquals(listOf(id(5), id(3), id(2), id(4)), loader.entries().map { it.conversation.rootId })
        assertTrue(relays.requests.none { (relay, filters) -> relay == stdRelay && filters.any { it.until != null } })

        loader.loadOlder()
        assertEquals(listOf(id(5), id(3), id(2), id(4), id(1)), loader.entries().map { it.conversation.rootId })
    }

    @Test
    fun loadOlderStopsWhenEveryRelayIsExhausted() = runTest {
        val relays = FakeRelays(mapOf(buzzRelay to listOf(ev(1, "b1"), ev(2, "b1"), ev(3, "b1"))))
        val loader = JoinedTimelineLoader(me, relays::fetch, pageSize = 2)
        loader.load(listOf(buzzGroup("b1")))
        assertTrue(loader.canLoadOlder)
        loader.loadOlder()
        assertEquals(listOf(id(3), id(2), id(1)), loader.entries().map { it.conversation.rootId })
        // 最古の秒を含めて読むので、ページいっぱいに返るあいだは続きがあるとみなす。
        loader.loadOlder()
        assertFalse(loader.canLoadOlder)
        assertEquals(listOf(id(3), id(2), id(1)), loader.entries().map { it.conversation.rootId })
    }

    @Test
    fun failingRelayIsReportedAndOthersAreStillShown() = runTest {
        val relays = FakeRelays(mapOf(stdRelay to listOf(ev(3, "s1"))), failing = setOf(buzzRelay))
        val loader = JoinedTimelineLoader(me, relays::fetch, pageSize = 10)
        loader.load(listOf(buzzGroup("b1"), stdGroup("s1")))

        assertEquals(listOf(id(3)), loader.entries().map { it.conversation.rootId })
        assertEquals(setOf(buzzRelay), loader.failedRelays)
    }

    @Test
    fun mutedAuthorsAreHidden() = runTest {
        val relays = FakeRelays(mapOf(buzzRelay to listOf(ev(1, "b1"), ev(2, "b1", pubkey = admin))))
        val loader = JoinedTimelineLoader(me, relays::fetch, pageSize = 10)
        loader.load(listOf(buzzGroup("b1")))
        assertEquals(listOf(id(1)), loader.entries(muted = setOf(admin)).map { it.conversation.rootId })
    }

    @Test
    fun entriesOpenTheThreadOnlyWhenThereIsOne() {
        fun entry(root: NostrEvent?, reply: NostrEvent?) = JoinedTimelineEntry(
            GroupRef(buzzRelay, "b1"),
            GroupConversation(
                rootId = id(1),
                root = root?.let { GroupMessage(it, it.pubkey, it.content, null, null, emptyList()) },
                latestReply = reply?.let { GroupMessage(it, it.pubkey, it.content, null, null, emptyList()) },
                replyCount = if (reply == null) 0 else 1,
                lastActivity = 1,
            ),
        )
        assertFalse(entry(ev(1, "b1"), null).opensThread)
        assertTrue(entry(ev(1, "b1"), ev(2, "b1", tags = reply(1))).opensThread)
        assertTrue(entry(null, ev(2, "b1", tags = reply(1))).opensThread)
        assertTrue(entry(ev(1, "b1", kind = BUZZ_FORUM_POST_KIND), null).opensThread)
        assertTrue(entry(ev(1, "b1", kind = GROUP_THREAD_KIND), null).opensThread)
    }

    @Test
    fun entriesAreSplitIntoTodayAndEarlier() {
        val zone = TimeZone.UTC
        val now = 1_763_424_000L + 12 * 3600 // 2025-11-18 12:00 UTC
        fun entry(n: Int, at: Long) = JoinedTimelineEntry(
            GroupRef(buzzRelay, "b1"),
            GroupConversation(id(n), null, null, 0, at),
        )
        val today = entry(1, now - 3600)
        val startOfDay = entry(2, now - 12 * 3600)
        val yesterday = entry(3, now - 12 * 3600 - 1)
        val (first, rest) = splitTodayEntries(listOf(today, startOfDay, yesterday), now, zone)
        assertEquals(listOf(today, startOfDay), first)
        assertEquals(listOf(yesterday), rest)
    }

    @Test
    fun previewUsesTitlesForPostsAndPlainTextForMessages() {
        fun message(event: NostrEvent, body: String = event.content) = GroupMessage(event, event.pubkey, body, null, null, emptyList())
        assertEquals("Topic", timelineMessagePreview(message(ev(1, "g", kind = BUZZ_FORUM_POST_KIND, tags = listOf(listOf("title", "Topic")), content = "body")), GroupFlavor.Buzz))
        assertEquals("bold text", timelineMessagePreview(message(ev(2, "g", content = "**bold** text")), GroupFlavor.Buzz))
        assertEquals("first", timelineMessagePreview(message(ev(3, "g", content = "\n first \nsecond")), GroupFlavor.Standard))
    }

    @Test
    fun botsAreHiddenOnlyWhenAsked() = runTest {
        // bot1 はエージェントの発言（40002）を出した人、bot2 はメンバー一覧（39002）で役割が bot の人。
        val bot1 = "e".repeat(64)
        val bot2 = "f".repeat(64)
        val relays = FakeRelays(mapOf(
            buzzRelay to listOf(
                ev(1, "b1"),
                ev(2, "b1", kind = BUZZ_AGENT_MESSAGE_KIND, pubkey = bot1, content = """{"content":"agent"}"""),
                ev(3, "b1", pubkey = bot1),
                ev(4, "b2", pubkey = bot2),
                ev(5, "b1", tags = reply(1), pubkey = me),
                ev(6, "b1", tags = reply(1), pubkey = bot2),
                NostrEvent(id(20), relayKey, 1, GROUP_MEMBERS_KIND, listOf(listOf("d", "b2"), listOf("p", alice), listOf("p", bot2, "", "bot")), "", "sig"),
            ),
        ))
        val loader = JoinedTimelineLoader(me, relays::fetch, pageSize = 10)
        loader.load(listOf(buzzGroup("b1"), buzzGroup("b2")))

        assertEquals(listOf(id(1), id(4), id(3), id(2)), loader.entries().map { it.conversation.rootId })

        // 隠す bot の投稿は 2, 3, 4, 6 の4件（ミュートした人の分は数えない）。
        assertEquals(4, loader.hiddenBotPosts())
        assertEquals(2, loader.hiddenBotPosts(muted = setOf(bot2)))

        val hidden = loader.entries(hideBots = true)
        assertEquals(listOf(id(1)), hidden.map { it.conversation.rootId })
        // 最新の返信は人のものにする。件数は bot の返信も数える。
        assertEquals(id(5), hidden.single().conversation.latestReply?.event?.id)
        assertEquals(2, hidden.single().conversation.replyCount)
    }

    @Test
    fun botThreadsWithHumanRepliesKeepTheBotRootAndTheHumanReplies() = runTest {
        val bot = "e".repeat(64)
        val relays = FakeRelays(mapOf(
            buzzRelay to listOf(
                ev(1, "b1", kind = BUZZ_AGENT_MESSAGE_KIND, pubkey = bot, content = """{"content":"agent"}"""),
                ev(2, "b1", tags = reply(1), pubkey = me),
                ev(3, "b1", tags = reply(1), pubkey = bot),
            ),
        ))
        val loader = JoinedTimelineLoader(me, relays::fetch, pageSize = 10)
        loader.load(listOf(buzzGroup("b1")))

        val entry = loader.entries(hideBots = true).single()
        assertEquals(id(1), entry.conversation.rootId)
        assertTrue(entry.conversation.rootHidden)
        assertEquals(id(1), entry.conversation.root?.event?.id)
        assertEquals(id(2), entry.conversation.latestReply?.event?.id)
        assertEquals(2, entry.conversation.replyCount)
        assertTrue(entry.opensThread)
    }

    @Test
    fun memberListsAreNotReadForStandardRelays() = runTest {
        val relays = FakeRelays(mapOf(stdRelay to listOf(ev(1, "s1"))))
        val loader = JoinedTimelineLoader(me, relays::fetch, pageSize = 10)
        loader.load(listOf(stdGroup("s1")))

        assertTrue(relays.requests.flatMap { it.second }.none { GROUP_MEMBERS_KIND in it.kinds.orEmpty() })
        assertEquals(listOf(id(1)), loader.entries(hideBots = true).map { it.conversation.rootId })
    }
}
