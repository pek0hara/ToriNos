package com.nostr.torinos.chat

import com.nostr.torinos.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BuzzSearchTest {
    private val relayUrl = "wss://png.communities.buzz.xyz"
    private val relayKey = "f".repeat(64)
    private val me = "e".repeat(64)
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val general = "11111111-1111-1111-1111-111111111111"
    private val forum = "22222222-2222-2222-2222-222222222222"
    private var seq = 0

    private fun event(
        kind: Int,
        content: String,
        tags: List<List<String>> = listOf(listOf("h", general)),
        author: String = alice,
        createdAt: Long = 1_700_000_000L,
    ) = NostrEvent((++seq).toString().padStart(64, '0'), author, createdAt, kind, tags, content, "sig")

    private fun meta(id: String, name: String, archived: Boolean = false, type: String = "stream") = GroupMetadata(
        groupId = id, name = name, picture = "", about = "", isPrivate = false, isRestricted = false, isHidden = false,
        isClosed = false, supportedKinds = null, createdAt = 0, channelType = type, isArchived = archived,
    )

    private fun channel(name: String, joined: Boolean = true, archived: Boolean = false, type: String = "stream", id: String = name) =
        BuzzSearchChannel(GroupRef(relayUrl, id), meta(id, name, archived, type), joined)

    // ---- 名前の順位（name_match.dart） ----

    @Test
    fun matchRankFollowsOfficialOrder() {
        assertEquals(BuzzMatchRank.EXACT, buzzMatchRank("General", "general"))
        assertEquals(BuzzMatchRank.PREFIX, buzzMatchRank("general-chat", "gen"))
        assertEquals(BuzzMatchRank.WORD_START, buzzMatchRank("team-launch", "launch"))
        assertEquals(BuzzMatchRank.SUBSTRING, buzzMatchRank("relaunch", "launch"))
        assertEquals(BuzzMatchRank.WORD_RUNS, buzzMatchRank("team-launch-planning", "tlp"))
        assertEquals(BuzzMatchRank.IN_ORDER, buzzMatchRank("general", "gnl"))
        assertNull(buzzMatchRank("general", "xyz"))
    }

    @Test
    fun matchRankIgnoresSpacesInTypedTextForLooseMatches() {
        assertEquals(BuzzMatchRank.WORD_RUNS, buzzMatchRank("team-launch-planning", "t l p"))
    }

    @Test
    fun matchRankWorksForJapanese() {
        assertEquals(BuzzMatchRank.PREFIX, buzzMatchRank("雑談部屋", "雑談"))
        assertEquals(BuzzMatchRank.SUBSTRING, buzzMatchRank("雑談部屋", "部屋"))
    }

    @Test
    fun channelsRankByMatchThenArchivedThenJoinedFirst() {
        val ranked = rankBuzzSearchChannels(
            listOf(
                channel("relaunch"),
                channel("launch-old", archived = true),
                channel("launch-b", joined = false),
                channel("launch-a", joined = false),
                channel("launch-z"),
                channel("launch-y"),
            ),
            "launch",
        )
        assertEquals(listOf("launch-z", "launch-y", "launch-a", "launch-b", "launch-old", "relaunch"), ranked.map { it.metadata.name })
    }

    @Test
    fun channelsStripHashAndHideArchivedOrUnjoinedForBareHash() {
        val channels = listOf(channel("general"), channel("random", joined = false), channel("old", archived = true, joined = false))
        assertEquals(listOf("general"), rankBuzzSearchChannels(channels, "#gen").map { it.metadata.name })
        // 未参加のアーカイブ済みは出さない。
        assertEquals(emptyList(), rankBuzzSearchChannels(channels, "old").map { it.metadata.name })
        // `#` だけなら参加中だけ（一覧の順）。
        assertEquals(listOf("general"), rankBuzzSearchChannels(channels, "#").map { it.metadata.name })
    }

    @Test
    fun channelsExcludeDmsAndCapAtEight() {
        val many = (1..10).map { channel("chan$it") } + channel("chan-dm", type = "dm")
        val ranked = rankBuzzSearchChannels(many, "chan")
        assertEquals(BUZZ_SEARCH_CHANNEL_LIMIT, ranked.size)
        assertTrue(ranked.none { it.metadata.layout == GroupLayout.Direct })
    }

    // ---- 最近の検索 ----

    @Test
    fun recentSearchesPutNewestFirstDedupeAndCap() {
        var recent = emptyList<String>()
        listOf("one", "two", "three", "four", "five", "six", "seven").forEach { recent = recordRecentSearch(recent, it) }
        assertEquals(listOf("seven", "six", "five", "four", "three", "two"), recent)
        recent = recordRecentSearch(recent, "  FIVE ")
        assertEquals(listOf("FIVE", "seven", "six", "four", "three", "two"), recent)
        assertEquals(recent, recordRecentSearch(recent, "   "))
    }

    @Test
    fun recentSearchesRoundTrip() {
        val recent = listOf("検索", "hello world")
        assertEquals(recent, decodeRecentSearches(encodeRecentSearches(recent)))
        assertEquals(emptyList(), decodeRecentSearches(null))
        assertEquals(emptyList(), decodeRecentSearches(""))
    }

    // ---- フィルター ----

    @Test
    fun messageFilterMatchesOfficialMobile() {
        val filter = buzzMessageSearchFilter("  hello  ")
        assertEquals(listOf(9, 40002, 45001, 45003), filter.kinds)
        assertEquals("hello", filter.search)
        assertEquals(20, filter.limit)
        assertNull(filter.hTags)
    }

    @Test
    fun peopleFilterSearchesProfiles() {
        val filter = buzzPeopleSearchFilter("ali")
        assertEquals(listOf(0), filter.kinds)
        assertEquals("ali", filter.search)
        assertEquals(BUZZ_SEARCH_PEOPLE_REQUEST, filter.limit)
    }

    @Test
    fun prefixSearchNeedsThreeCharLastWordAndNoFullTextSyntax() {
        assertTrue(buzzPrefixSearchable("Open"))
        assertTrue(buzzPrefixSearchable("申請 opensats"))
        assertTrue(buzzPrefixSearchable("明日から"))
        // 最後の語が短い（2文字以下）。
        assertEquals(false, buzzPrefixSearchable("明日"))
        assertEquals(false, buzzPrefixSearchable("opensats gi"))
        // 語句（閉じた引用符）と除外（-語）は全文検索の書き方。
        assertEquals(false, buzzPrefixSearchable("\"open sats\""))
        assertEquals(false, buzzPrefixSearchable("open -sats"))
        assertTrue(buzzPrefixSearchable("built-in"))
        assertEquals(false, buzzPrefixSearchable("   "))
    }

    @Test
    fun relayModeFollowsSignerAndLastWord() {
        assertEquals(BuzzRelaySearchMode.Prefix, buzzRelaySearchMode("Open", signsSilently = true))
        assertEquals(BuzzRelaySearchMode.FullText, buzzRelaySearchMode("明日", signsSilently = true))
        assertEquals(BuzzRelaySearchMode.FullText, buzzRelaySearchMode("open -sats", signsSilently = true))
        assertEquals(BuzzRelaySearchMode.Socket, buzzRelaySearchMode("Open", signsSilently = false))
    }

    @Test
    fun queryBodyCarriesModeLimitAndPage() {
        assertEquals(
            """[{"kinds":[9,40002,45001,45003],"search":"open","search_mode":"prefix","limit":50}]""",
            buzzMessageQueryBody(" open ", prefix = true, limit = 50, page = 1),
        )
        assertEquals(
            """[{"kinds":[9,40002,45001,45003],"search":"明日","limit":500,"page":2}]""",
            buzzMessageQueryBody("明日", prefix = false, limit = 500, page = 2),
        )
    }

    // ---- 正規化と手元の照合 ----

    @Test
    fun normalizeFoldsCaseWidthAndKatakana() {
        assertEquals("opensats の しんせい", buzzNormalize("ＯｐｅｎＳａｔｓ　の　シンセイ"))
        assertEquals("おーぷん", buzzNormalize("オープン"))
    }

    @Test
    fun localMatchFindsMiddleOfJapaneseSentence() {
        val text = buzzNormalize("明日！OpenSatsの申請進めようと思う！")
        assertEquals(BuzzLocalRank.SUBSTRING, buzzLocalMatchRank(text, parseBuzzLocalQuery("申請")!!))
        assertEquals(BuzzLocalRank.WORD_START, buzzLocalMatchRank(text, parseBuzzLocalQuery("open")!!))
        assertEquals(BuzzLocalRank.WHOLE_WORD, buzzLocalMatchRank(text, parseBuzzLocalQuery("明日")!!))
        // すべての語が要る。行の良さは悪いほうの語。
        assertEquals(BuzzLocalRank.SUBSTRING, buzzLocalMatchRank(text, parseBuzzLocalQuery("明日 申請")!!))
        assertNull(buzzLocalMatchRank(text, parseBuzzLocalQuery("明日 天気")!!))
        // 除外と引用符。
        assertNull(buzzLocalMatchRank(text, parseBuzzLocalQuery("明日 -申請")!!))
        assertEquals(BuzzLocalRank.SUBSTRING, buzzLocalMatchRank(text, parseBuzzLocalQuery("\"申請進め\"")!!))
        assertNull(parseBuzzLocalQuery("-申請"))
        assertNull(parseBuzzLocalQuery("   "))
    }

    @Test
    fun channelRankUsesNormalizedNames() {
        assertEquals(BuzzMatchRank.PREFIX, buzzMatchRank("ＤＥＶ-開発", "dev"))
        assertEquals(BuzzMatchRank.EXACT, buzzMatchRank("ザツダン", "ざつだん"))
    }

    // ---- まとめ方 ----

    @Test
    fun mergePutsLocalFirstAndDropsDuplicates() {
        val a = event(9, "a"); val b = event(9, "b"); val c = event(9, "c")
        val local = buzzMessageHits(listOf(b, a), relayUrl, relayKey)
        val relay = buzzMessageHits(listOf(a, c), relayUrl, relayKey)
        assertEquals(listOf(b.id, a.id, c.id), mergeBuzzSearchHits(local, relay).map { it.id })
    }

    // ---- メッセージの当たり ----

    @Test
    fun hitsKeepRelayOrderAndNeedExactlyOneChannel() {
        val first = event(9, "first")
        val noChannel = event(9, "none", tags = emptyList())
        val twoChannels = event(9, "two", tags = listOf(listOf("h", general), listOf("h", forum)))
        val second = event(9, "second", createdAt = 1)
        val hits = buzzMessageHits(listOf(first, noChannel, twoChannels, second), relayUrl, relayKey)
        assertEquals(listOf(first.id, second.id), hits.map { it.id })
        assertEquals(GroupRef(relayUrl, general), hits.first().ref)
    }

    @Test
    fun hitsReadAgentBodyAndRelaySignedAuthor() {
        val agent = event(BUZZ_AGENT_MESSAGE_KIND, """{"content":"agent **reply**"}""")
        val relayed = event(9, "relayed", tags = listOf(listOf("h", general), listOf("actor", bob)), author = relayKey)
        val hits = buzzMessageHits(listOf(agent, relayed), relayUrl, relayKey)
        assertEquals("agent reply", hits[0].preview)
        assertEquals(bob, hits[1].author)
    }

    @Test
    fun hitsOpenThreadOrChannel() {
        val top = event(9, "top")
        val reply = event(9, "reply", tags = listOf(listOf("h", general), listOf("e", "r".repeat(64), "", "root"), listOf("e", "p".repeat(64), "", "reply")))
        val post = event(BUZZ_FORUM_POST_KIND, "Title\nbody", tags = listOf(listOf("h", forum)))
        val comment = event(BUZZ_FORUM_COMMENT_KIND, "comment", tags = listOf(listOf("h", forum), listOf("e", post.id, "", "root")))
        val hits = buzzMessageHits(listOf(top, reply, post, comment), relayUrl, relayKey)
        assertEquals(BuzzSearchTarget.Channel(GroupRef(relayUrl, general)), hits[0].target)
        assertEquals(BuzzSearchTarget.Thread(GroupRef(relayUrl, general), "r".repeat(64)), hits[1].target)
        assertEquals(BuzzSearchTarget.Thread(GroupRef(relayUrl, forum), post.id), hits[2].target)
        assertEquals(BuzzSearchTarget.Thread(GroupRef(relayUrl, forum), post.id), hits[3].target)
    }

    @Test
    fun previewIsPlainTextAndCutsLongBodies() {
        assertEquals("見出し 本文 です", buzzSearchPreview("# 見出し\n\n本文 **です**"))
        assertEquals("📷 写真", buzzSearchPreview("![a](https://x/y.png)\n\n写真"))
        val long = "a".repeat(BUZZ_SEARCH_RICH_PREVIEW_LIMIT + 10)
        val preview = buzzSearchPreview(long)
        assertEquals(BUZZ_SEARCH_RICH_PREVIEW_LIMIT + 1, preview.length)
        assertTrue(preview.endsWith("…"))
    }

    // ---- 人 ----

    @Test
    fun peopleKeepNewestProfileDropSelfAndCap() {
        val old = event(0, """{"name":"alice-old"}""", tags = emptyList(), author = alice, createdAt = 1)
        val new = event(0, """{"name":"alice","display_name":"Alice"}""", tags = emptyList(), author = alice, createdAt = 2)
        val mine = event(0, """{"name":"me"}""", tags = emptyList(), author = me)
        val verified = event(30382, "{}", tags = emptyList(), author = relayKey)
        val people = buzzSearchPeople(listOf(old, new, mine, verified), me)
        assertEquals(listOf(alice), people.map { it.pubkey })
        assertEquals("Alice", people.single().profile.bestName)

        val many = (1..12).map { i -> event(0, """{"name":"p$i"}""", tags = emptyList(), author = i.toString().padStart(64, '0')) }
        assertEquals(BUZZ_SEARCH_PEOPLE_SHOWN, buzzSearchPeople(many, me).size)
    }

    @Test
    fun profileSearchBodyAndResponse() {
        assertEquals("""{"query":"ali","limit":24}""", buzzProfileSearchBody(" ali "))
        val response = """[{"id":"${"1".repeat(64)}","pubkey":"$alice","created_at":5,"kind":0,"tags":[],"content":"{\"name\":\"alice\"}","sig":"s"}]"""
        assertEquals(listOf(alice), parseBuzzEventArray(response, verify = { true })?.map { it.pubkey })
        assertEquals(emptyList(), parseBuzzEventArray(response, verify = { false }))
        assertNull(parseBuzzEventArray("not json", verify = { true }))
    }
}
