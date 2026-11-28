package com.nostr.torinos.chat

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.matches
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
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
    private class FakeRelays(val stored: Map<String, List<NostrEvent>>, var failing: Set<String> = emptySet()) {
        val requests = mutableListOf<Pair<String, List<NostrFilter>>>()
        /** リレーごとの、答えを止めておく門。開くまで、そのリレーへの要求は返らない。 */
        val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
        /** リレーごとに、最初のこの回数の要求には答えない（起動直後の接続待ちなど）。 */
        val failFirst = mutableMapOf<String, Int>()
        private var inFlight = 0
        /** 同時に答えを待っていた要求の数の最大。 */
        var maxInFlight = 0
        suspend fun fetch(relayUrl: String, filters: List<NostrFilter>): TimelineFetch {
            requests += relayUrl to filters
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            try {
                yield()
                gates[relayUrl]?.await()
            } finally {
                inFlight--
            }
            if (relayUrl in failing) return TimelineFetch(emptyList(), answered = false)
            val left = failFirst[relayUrl] ?: 0
            if (left > 0) {
                failFirst[relayUrl] = left - 1
                return TimelineFetch(emptyList(), answered = false)
            }
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
    fun threadsOnlyKeepsThreadsIAmPartOf() = runTest {
        val carol = "e".repeat(64)
        val mention = listOf(listOf("p", me))
        val relays = FakeRelays(mapOf(
            buzzRelay to listOf(
                ev(1, "b1"), ev(2, "b1", tags = reply(1), pubkey = me), // 自分が返信した
                ev(3, "b1", pubkey = me), ev(4, "b1", tags = reply(3)), // 自分の元の投稿
                ev(5, "b1"), ev(6, "b1", tags = reply(5) + mention), // 返信で自分を指している
                ev(7, "b1"), ev(8, "b1", tags = reply(7)), // 関わっていない
                ev(9, "b1", pubkey = me), // 返信の無い自分の投稿はスレッドではない
                ev(10, "b1"), ev(11, "b1", tags = reply(10) + mention, pubkey = carol), // ミュートした人の返信だけ
            ),
            stdRelay to listOf(ev(12, "s1", kind = GROUP_THREAD_KIND, pubkey = me)), // 返信の無い kind 11
        ))
        val loader = JoinedTimelineLoader(me, relays::fetch, pageSize = 20)
        loader.load(listOf(buzzGroup("b1"), stdGroup("s1")))

        val threads = loader.entries(muted = setOf(carol)).filter { it.isMyThread }
        assertEquals(listOf(id(12), id(5), id(3), id(1)), threads.map { it.conversation.rootId })
    }

    @Test
    fun threadsOnlyCountsAHiddenBotRoot() = runTest {
        val bot = "f".repeat(64)
        val relays = FakeRelays(mapOf(
            buzzRelay to listOf(ev(1, "b1", kind = BUZZ_AGENT_MESSAGE_KIND, pubkey = bot, tags = listOf(listOf("p", me)), content = """{"content":"agent"}"""), ev(2, "b1", tags = reply(1))),
        ))
        val loader = JoinedTimelineLoader(me, relays::fetch, pageSize = 20)
        loader.load(listOf(buzzGroup("b1")))

        val threads = loader.entries(hideBots = true).filter { it.isMyThread }
        assertEquals(listOf(id(1)), threads.map { it.conversation.rootId })
    }

    // ---- 読み込みの見直し（docs/chat-joined-timeline-design.md 3.4） ----

    @Test
    fun myThreadOutsideTheRecentPageIsReadDirectly() = runTest {
        val relays = FakeRelays(mapOf(
            buzzRelay to listOf(
                ev(1, "b1"), ev(2, "b1", tags = reply(1), pubkey = me), // 古いスレッドに自分が返信した
                ev(3, "b1"), ev(4, "b1", tags = reply(3) + listOf(listOf("p", me))), // 古いスレッドでメンションされた
                ev(10, "b1"), ev(11, "b1"), ev(12, "b1"), // 最近の投稿（ページは2件）
            ),
        ))
        val loader = JoinedTimelineLoader(me, relays::fetch, pageSize = 2, readMine = true)
        loader.load(listOf(buzzGroup("b1")))

        assertEquals(listOf(id(3), id(1)), loader.myThreads().map { it.conversation.rootId })
        // タイムラインは今まで通り、最近のページの範囲だけ（古い投稿を飛び飛びに混ぜない）。
        assertEquals(listOf(id(12), id(11)), loader.entries().map { it.conversation.rootId })
    }

    @Test
    fun myThreadsAreNotReadUnlessAsked() = runTest {
        val relays = FakeRelays(mapOf(buzzRelay to listOf(ev(1, "b1"))))
        JoinedTimelineLoader(me, relays::fetch, pageSize = 2).load(listOf(buzzGroup("b1")))
        assertTrue(relays.requests.flatMap { it.second }.none { it.authors == listOf(me) || it.pTags == listOf(me) })
    }

    @Test
    fun olderThreadsPageOnlyMyPosts() = runTest {
        val relays = FakeRelays(mapOf(
            buzzRelay to listOf(
                ev(1, "b1"), ev(2, "b1", tags = reply(1), pubkey = me),
                ev(3, "b1"), ev(4, "b1", tags = reply(3), pubkey = me),
                ev(5, "b1"), ev(6, "b1", tags = reply(5), pubkey = me),
                ev(20, "b1"), ev(21, "b1"),
            ),
        ))
        val loader = JoinedTimelineLoader(me, relays::fetch, pageSize = 2, readMine = true)
        loader.load(listOf(buzzGroup("b1")))
        assertEquals(listOf(id(5), id(3)), loader.myThreads().map { it.conversation.rootId })
        assertTrue(loader.canLoadOlderThreads)

        relays.requests.clear()
        loader.loadOlderThreads()
        assertEquals(listOf(id(5), id(3), id(1)), loader.myThreads().map { it.conversation.rootId })
        // 全員の投稿はさかのぼらない。
        val paged = relays.requests.flatMap { it.second }.filter { it.until != null }
        assertTrue(paged.isNotEmpty())
        assertTrue(paged.all { it.authors == listOf(me) || it.pTags == listOf(me) })
    }

    private class Clock(var now: Long = 1_000_000L) {
        fun read() = now
    }

    @Test
    fun updateReadsOnlyWhatIsNewSinceTheLastLoad() = runTest {
        val stored = mutableListOf(ev(100, "b1"), ev(110, "b1"))
        val relays = FakeRelays(mapOf(buzzRelay to stored))
        val clock = Clock()
        val loader = JoinedTimelineLoader(me, relays::fetch, pageSize = 10, readMine = true, clock = clock::read)
        loader.load(listOf(buzzGroup("b1")))

        stored += ev(200, "b1")
        stored += ev(210, "b1", tags = reply(110))
        stored += ev(220, "b1", kind = 5, tags = listOf(listOf("e", id(100))))
        relays.requests.clear()
        clock.now += 5 * 60_000L
        loader.update(listOf(buzzGroup("b1")))

        assertEquals(listOf(id(110), id(200)), loader.entries().map { it.conversation.rootId })
        assertEquals(id(210), loader.entries().first().conversation.latestReply?.event?.id)
        // 最新の投稿（110）から 60 秒戻した時刻から読む。いちからは読まない。
        val first = relays.requests.first().second
        assertTrue(first.all { it.since == 110L - 60 })
        assertTrue(relays.requests.flatMap { it.second }.none { it.since == null && it.ids == null && it.eTags == null && it.rootEventTags == null && it.dTags == null })
    }

    @Test
    fun updateReloadsARelayWhenTooMuchHappened() = runTest {
        val stored = mutableListOf(ev(100, "b1"))
        val relays = FakeRelays(mapOf(buzzRelay to stored))
        val clock = Clock()
        val loader = JoinedTimelineLoader(me, relays::fetch, pageSize = 2, readMine = true, clock = clock::read)
        loader.load(listOf(buzzGroup("b1")))

        stored += listOf(ev(200, "b1"), ev(201, "b1"), ev(202, "b1"))
        clock.now += 5 * 60_000L
        relays.requests.clear()
        loader.update(listOf(buzzGroup("b1")))

        // 差分がページいっぱいなので、いちから読み直す（間の抜けたタイムラインにしない）。
        assertTrue(relays.requests.flatMap { it.second }.any { it.since == null && it.limit == 2 && it.authors == null && it.pTags == null })
        assertEquals(listOf(id(202), id(201)), loader.entries().map { it.conversation.rootId })
    }

    @Test
    fun updateReloadsARelayAfterALongGap() = runTest {
        val relays = FakeRelays(mapOf(buzzRelay to listOf(ev(100, "b1"))))
        val clock = Clock()
        val loader = JoinedTimelineLoader(me, relays::fetch, pageSize = 10, readMine = true, clock = clock::read)
        loader.load(listOf(buzzGroup("b1")))

        clock.now += 31 * 60_000L
        relays.requests.clear()
        loader.update(listOf(buzzGroup("b1")))
        assertTrue(relays.requests.flatMap { it.second }.none { it.since != null })
    }

    @Test
    fun aRelayThatAnsweredNothingIsReadOnceMore() = runTest {
        val relays = FakeRelays(mapOf(buzzRelay to listOf(ev(100, "b1")), stdRelay to listOf(ev(101, "s1"))))
        // 起動直後で、最初の要求（1ページ目と自分の投稿）に答えない。
        relays.failFirst[buzzRelay] = 2
        val loader = JoinedTimelineLoader(me, relays::fetch, pageSize = 10, readMine = true)
        loader.load(listOf(buzzGroup("b1"), stdGroup("s1")))
        assertEquals(listOf(id(101), id(100)), loader.entries().map { it.conversation.rootId })
        assertEquals(emptySet(), loader.failedRelays)
        // 読めたリレーは読み直さない。
        assertEquals(1, relays.requests.count { (url, filters) -> url == stdRelay && filters.any { it.limit == 10 && it.authors == null && it.pTags == null } })
    }

    @Test
    fun aRelayThatNeverAnswersIsReadOnlyTwice() = runTest {
        val relays = FakeRelays(mapOf(buzzRelay to listOf(ev(100, "b1"))), failing = setOf(buzzRelay))
        val loader = JoinedTimelineLoader(me, relays::fetch, pageSize = 10)
        loader.load(listOf(buzzGroup("b1")))
        assertEquals(setOf(buzzRelay), loader.failedRelays)
        assertEquals(2, relays.requests.size)
    }

    @Test
    fun updateKeepsThePreviousResultWhenTheReloadFails() = runTest {
        val relays = FakeRelays(mapOf(buzzRelay to listOf(ev(100, "b1"))))
        val clock = Clock()
        val loader = JoinedTimelineLoader(me, relays::fetch, pageSize = 10, readMine = true, clock = clock::read)
        loader.load(listOf(buzzGroup("b1")))

        // 間が空いたのでいちから読むが、リレーが答えない（バックグラウンドから戻った直後など）。前の結果は消さない。
        clock.now += 31 * 60_000L
        relays.failing = setOf(buzzRelay)
        loader.update(listOf(buzzGroup("b1")))
        assertEquals(listOf(id(100)), loader.entries().map { it.conversation.rootId })
        assertEquals(setOf(buzzRelay), loader.failedRelays)

        // 次に答えたら、いちから読み直した結果に替える。
        relays.failing = emptySet()
        loader.update(listOf(buzzGroup("b1")))
        assertEquals(listOf(id(100)), loader.entries().map { it.conversation.rootId })
        assertEquals(emptySet(), loader.failedRelays)
    }

    @Test
    fun updateReadsOnlyWhatIsNewWhenTheGroupsComeInAnotherOrder() = runTest {
        val relays = FakeRelays(mapOf(buzzRelay to listOf(ev(100, "b1"), ev(101, "b2"))))
        val clock = Clock()
        val loader = JoinedTimelineLoader(me, relays::fetch, pageSize = 10, readMine = true, clock = clock::read)
        loader.load(listOf(buzzGroup("b1"), buzzGroup("b2")))

        clock.now += 5 * 60_000L
        relays.requests.clear()
        loader.update(listOf(buzzGroup("b2"), buzzGroup("b1")))
        assertTrue(relays.requests.first().second.all { it.since == 101L - 60 })
    }

    @Test
    fun updateReloadsOnlyTheRelaysThatChangedOrFailed() = runTest {
        val relays = FakeRelays(mapOf(buzzRelay to listOf(ev(100, "b1")), stdRelay to listOf(ev(101, "s1"))), failing = setOf(stdRelay))
        val clock = Clock()
        val loader = JoinedTimelineLoader(me, relays::fetch, pageSize = 10, readMine = true, clock = clock::read)
        loader.load(listOf(buzzGroup("b1"), stdGroup("s1")))

        clock.now += 5 * 60_000L
        relays.requests.clear()
        loader.update(listOf(buzzGroup("b1"), buzzGroup("b2"), stdGroup("s1")))
        // b2 が増えた buzz と、前回読めなかった std は、いちから読む。
        assertTrue(relays.requests.filter { it.first == buzzRelay }.flatMap { it.second }.any { it.since == null && it.limit == 10 })
        assertTrue(relays.requests.filter { it.first == stdRelay }.flatMap { it.second }.any { it.since == null && it.limit == 10 })
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
        // 名前の横に Bot の印を出す人。
        assertEquals(setOf(bot1, bot2), loader.bots())
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

    @Test
    fun relaysThatAnsweredAreShownWithoutWaitingForSlowOnes() = runTest {
        val relays = FakeRelays(mapOf(
            buzzRelay to listOf(ev(1, "b1", createdAt = 100), ev(2, "b1", createdAt = 101)),
            stdRelay to listOf(ev(3, "s1", createdAt = 50)),
        ))
        val slow = CompletableDeferred<Unit>()
        relays.gates[stdRelay] = slow
        val loader = JoinedTimelineLoader(me, relays::fetch, pageSize = 2)
        var progress = 0
        val job = launch { loader.load(listOf(buzzGroup("b1"), stdGroup("s1"))) { progress++ } }
        runCurrent()

        // buzz は読み終えた。std はまだ答えていないので、下限にもしない（buzz の行がそのまま出る）。
        assertEquals(1, progress)
        assertEquals(listOf(id(2), id(1)), loader.entries().map { it.conversation.rootId })

        slow.complete(Unit)
        job.join()
        assertEquals(2, progress)
        // std が答えると、buzz（ページいっぱい）の下限 100 より古い std の 50 は出さない（3.1）。
        assertEquals(listOf(id(2), id(1)), loader.entries().map { it.conversation.rootId })
        assertTrue(loader.canLoadOlder)
    }

    @Test
    fun followUpAndDeletionsOfThePageAreRequestedTogether() = runTest {
        val relays = FakeRelays(mapOf(
            buzzRelay to listOf(ev(1, "b1"), ev(2, "b1", tags = reply(1)), ev(10, "b1", kind = 5, tags = listOf(listOf("e", id(1))))),
        ))
        val loader = JoinedTimelineLoader(me, relays::fetch, pageSize = 10)
        loader.load(listOf(buzzGroup("b1")))

        assertEquals(2, relays.maxInFlight)
        assertEquals(emptyList(), loader.entries().map { it.conversation.rootId })
        // 続きで新しく読めた投稿が無いので、削除・編集をもう一度は読まない（ページ・続き・削除の3回）。
        assertEquals(3, relays.requests.size)
    }

    @Test
    fun deletionsOfRepliesFoundInTheFollowUpAreApplied() = runTest {
        // ページ（2件）より古い返信 2 は、続きで読める。その削除は後から読む。
        val relays = FakeRelays(mapOf(
            buzzRelay to listOf(
                ev(1, "b1", content = "root"), ev(2, "b1", tags = reply(1)), ev(8, "b1", tags = reply(1)), ev(9, "b1", tags = reply(1)),
                ev(20, "b1", kind = 5, tags = listOf(listOf("e", id(2))), createdAt = 3),
            ),
        ))
        val loader = JoinedTimelineLoader(me, relays::fetch, pageSize = 2)
        loader.load(listOf(buzzGroup("b1")))

        val conversation = loader.entries().single().conversation
        assertEquals(2, conversation.replyCount)
        assertEquals(id(9), conversation.latestReply?.event?.id)
    }
}
