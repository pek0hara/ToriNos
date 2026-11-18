package com.nostr.torinos.chat

import com.nostr.torinos.crypto.sha256
import com.nostr.torinos.crypto.toHex
import com.nostr.torinos.model.NostrEvent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

// docs/buzz-admin-design.md 4.2
class CommunityAdministrationTest {
    private val relayKey = "f".repeat(64)
    private val me = "a".repeat(64)
    private val alice = "b".repeat(64)
    private val bob = "c".repeat(64)
    private val carol = "d".repeat(64)

    private fun roster(vararg tags: List<String>, signer: String = relayKey, createdAt: Long = 100, eventId: String = "1".repeat(64)) =
        NostrEvent(eventId, signer, createdAt, COMMUNITY_ROSTER_KIND, listOf(listOf("-")) + tags.toList(), "", "sig")

    // ---- メンバー一覧（kind 13534） ----

    @Test
    fun rosterIsReadFromRelaySignedSnapshot() {
        val event = roster(
            listOf("member", me, "owner"),
            listOf("member", alice, "admin"),
            listOf("member", bob, "member"),
            listOf("member", carol),
            // 重複は最初のものを使う。
            listOf("member", bob, "owner"),
            listOf("member", "not-a-key", "owner"),
            listOf("p", "e".repeat(64)),
        )
        assertEquals(
            listOf(
                CommunityMember(me, CommunityRole.Owner),
                CommunityMember(alice, CommunityRole.Admin),
                CommunityMember(bob, CommunityRole.Member),
                CommunityMember(carol, CommunityRole.Member),
            ),
            parseCommunityRoster(event, relayKey),
        )
    }

    @Test
    fun rosterSignedBySomeoneElseIsIgnored() {
        assertNull(parseCommunityRoster(roster(listOf("member", me, "owner"), signer = alice), relayKey))
        val wrongKind = NostrEvent("1".repeat(64), relayKey, 1, 39002, listOf(listOf("member", me, "owner")), "", "sig")
        assertNull(parseCommunityRoster(wrongKind, relayKey))
    }

    @Test
    fun latestRosterWins() {
        val old = roster(listOf("member", me, "member"), createdAt = 100, eventId = "1".repeat(64))
        val new = roster(listOf("member", me, "admin"), createdAt = 200, eventId = "2".repeat(64))
        val forged = roster(listOf("member", me, "owner"), signer = alice, createdAt = 300, eventId = "3".repeat(64))
        assertEquals(listOf(CommunityMember(me, CommunityRole.Admin)), latestCommunityRoster(listOf(old, new, forged), relayKey))
        assertNull(latestCommunityRoster(listOf(forged), relayKey))
    }

    @Test
    fun rosterIsOrderedOwnersAdminsThenOthersWithSelfLast() {
        val members = listOf(
            CommunityMember(bob, CommunityRole.Member),
            CommunityMember(me, CommunityRole.Owner),
            CommunityMember(carol, CommunityRole.Admin),
            CommunityMember(alice, CommunityRole.Owner),
        )
        val names = mapOf(bob to "Bob", carol to "Carol", alice to "Alice")
        assertEquals(listOf(alice, carol, bob, me), orderCommunityMembers(members, me) { names[it] }.map { it.pubkey })
    }

    // ---- 操作できること（buzz-app の allowedActions と同じ） ----

    @Test
    fun ownersPromoteDemoteAndRemove() {
        val owner = CommunityRole.Owner
        assertEquals(
            listOf(CommunityAction.Promote, CommunityAction.Remove),
            allowedCommunityActions(owner, CommunityMember(bob, CommunityRole.Member), self = false),
        )
        assertEquals(
            listOf(CommunityAction.Demote, CommunityAction.Remove),
            allowedCommunityActions(owner, CommunityMember(bob, CommunityRole.Admin), self = false),
        )
        assertEquals(emptyList(), allowedCommunityActions(owner, CommunityMember(bob, CommunityRole.Owner), self = false))
        assertEquals(emptyList(), allowedCommunityActions(owner, CommunityMember(me, CommunityRole.Owner), self = true))
    }

    @Test
    fun adminsOnlyRemoveMembers() {
        val admin = CommunityRole.Admin
        assertEquals(listOf(CommunityAction.Remove), allowedCommunityActions(admin, CommunityMember(bob, CommunityRole.Member), self = false))
        assertEquals(emptyList(), allowedCommunityActions(admin, CommunityMember(bob, CommunityRole.Admin), self = false))
        assertEquals(emptyList(), allowedCommunityActions(admin, CommunityMember(bob, CommunityRole.Owner), self = false))
        assertEquals(emptyList(), allowedCommunityActions(admin, CommunityMember(me, CommunityRole.Admin), self = true))
    }

    @Test
    fun membersAndOutsidersDoNothing() {
        assertEquals(emptyList(), allowedCommunityActions(CommunityRole.Member, CommunityMember(bob, CommunityRole.Member), self = false))
        assertEquals(emptyList(), allowedCommunityActions(null, CommunityMember(bob, CommunityRole.Member), self = false))
        assertEquals(false, managesCommunity(CommunityRole.Member))
        assertEquals(false, managesCommunity(null))
        assertEquals(true, managesCommunity(CommunityRole.Admin))
        assertEquals(true, managesCommunity(CommunityRole.Owner))
    }

    // ---- 命令の形 ----

    @Test
    fun removeAndRoleCommandsHaveNoGroupTag() {
        assertEquals(GroupEventDraft(COMMUNITY_REMOVE_MEMBER_KIND, listOf(listOf("p", bob)), ""), buildCommunityRemove(bob))
        assertEquals(
            GroupEventDraft(COMMUNITY_CHANGE_ROLE_KIND, listOf(listOf("p", bob), listOf("role", "admin")), ""),
            buildCommunityRole(bob, CommunityRole.Admin),
        )
        assertEquals(listOf("role", "member"), buildCommunityRole(bob.uppercase(), CommunityRole.Member).tags.last())
        assertFailsWith<IllegalArgumentException> { buildCommunityRole(bob, CommunityRole.Owner) }
        assertFailsWith<IllegalArgumentException> { buildCommunityRemove("npub1x") }
    }

    @Test
    fun communityCommandsGoOnlyToThatBuzzRelay() {
        listOf(buildCommunityRemove(bob), buildCommunityRole(bob, CommunityRole.Member)).forEach { draft ->
            val event = NostrEvent("2".repeat(64), me, 1, draft.kind, draft.tags, "", "sig")
            assertNull(buzzCommandViolation(event, setOf("wss://x"), "wss://x", GroupFlavor.Buzz))
            assertEquals(BuzzCommandViolation.WrongRelay, buzzCommandViolation(event, setOf("wss://x", "wss://y"), "wss://x", GroupFlavor.Buzz))
            assertEquals(BuzzCommandViolation.NotBuzz, buzzCommandViolation(event, setOf("wss://x"), "wss://x", GroupFlavor.Standard))
        }
        // 直接追加（9030）は UI で使わないので送れない。
        val add = NostrEvent("2".repeat(64), me, 1, 9030, listOf(listOf("p", bob), listOf("role", "member")), "", "sig")
        assertEquals(BuzzCommandViolation.UnsupportedKind, buzzCommandViolation(add, setOf("wss://x"), "wss://x", GroupFlavor.Buzz))
    }

    // ---- 招待 ----

    @Test
    fun inviteRequestIsBounded() {
        assertEquals("""{"ttl_secs":604800,"max_uses":1}""", inviteRequestBody(InviteRequest(604_800, 1)))
        assertEquals("""{"ttl_secs":3600,"max_uses":null}""", inviteRequestBody(InviteRequest(3_600, null)))
        assertFailsWith<IllegalArgumentException> { InviteRequest(59, 1) }
        assertFailsWith<IllegalArgumentException> { InviteRequest(2_592_001, 1) }
        assertFailsWith<IllegalArgumentException> { InviteRequest(3_600, 0) }
        assertFailsWith<IllegalArgumentException> { InviteRequest(3_600, 10_001) }
    }

    @Test
    fun mintedInviteMustPointAtTheCommunity() {
        val body = """{"code":"v2.abc","expires_at":1800000000,"max_uses":1,"uses_remaining":1,"url":"https://png.example/invite/v2.abc"}"""
        assertEquals(
            MintedInvite("v2.abc", "https://png.example/invite/v2.abc", 1_800_000_000, 1, 1),
            parseMintedInvite(body, "https://png.example"),
        )
        val unlimited = """{"code":"v2.abc","expires_at":1800000000,"max_uses":null,"uses_remaining":null,"url":"https://png.example/invite/v2.abc"}"""
        assertEquals(null, parseMintedInvite(unlimited, "https://png.example")?.maxUses)
        // 別のホストのリンクは配らない。
        assertNull(parseMintedInvite(body.replace("png.example/invite", "evil.example/invite"), "https://png.example"))
        assertNull(parseMintedInvite(body.replace("/invite/", "/other/"), "https://png.example"))
        assertNull(parseMintedInvite("""{"code":"v2.abc"}""", "https://png.example"))
        assertNull(parseMintedInvite("nope", "https://png.example"))
    }

    @Test
    fun inviteRefusalsAreTranslated() {
        assertEquals("招待を作れるのはオーナーと管理者だけです", inviteRefusalMessage(403, """{"error":"only relay owners and admins can create invites"}"""))
        assertEquals("期限・回数の指定が正しくありません", inviteRefusalMessage(400, """{"error":"ttl_secs must be between 60 and 2592000"}"""))
        assertEquals("一時的に変更できません。しばらくしてからお試しください", inviteRefusalMessage(503, """{"error":"community writes are temporarily unavailable"}"""))
        assertEquals("招待を作れませんでした（500）", inviteRefusalMessage(500, "oops"))
    }

    @Test
    fun inviteIsMintedWithNip98CoveringTheBody() = runTest {
        val requests = mutableListOf<Triple<String, String, String?>>()
        val signed = mutableListOf<NostrEvent>()
        val http = object : BuzzHttp {
            override suspend fun get(url: String) = BuzzHttpResponse(404, "{}")
            override suspend fun postJson(url: String, body: String, authorization: String?): BuzzHttpResponse {
                requests += Triple(url, body, authorization)
                return BuzzHttpResponse(200, """{"code":"v2.abc","expires_at":1800000000,"max_uses":10,"uses_remaining":10,"url":"https://png.example/invite/v2.abc"}""")
            }
        }
        val signer = BuzzRequestSigner { kind, tags, content -> NostrEvent("9".repeat(64), me, 1, kind, tags, content, "sig").also { signed += it } }
        val result = BuzzInviteService(http, signer).mint("wss://png.example", InviteRequest(86_400, 10))

        assertIs<InviteMintResult.Minted>(result)
        assertEquals("https://png.example/invite/v2.abc", result.invite.url)
        val (url, body, auth) = requests.single()
        assertEquals("https://png.example/api/invites", url)
        assertEquals("""{"ttl_secs":86400,"max_uses":10}""", body)
        assertEquals(nostrAuthorizationHeader(signed.single()), auth)
        val event = signed.single()
        assertEquals(HTTP_AUTH_KIND, event.kind)
        assertEquals(
            listOf(listOf("u", url), listOf("method", "POST"), listOf("payload", sha256(body.encodeToByteArray()).toHex())),
            event.tags,
        )
    }

    @Test
    fun inviteRefusalIsReported() = runTest {
        val http = object : BuzzHttp {
            override suspend fun get(url: String) = BuzzHttpResponse(404, "{}")
            override suspend fun postJson(url: String, body: String, authorization: String?) =
                BuzzHttpResponse(403, """{"error":"only relay owners and admins can create invites"}""")
        }
        val signer = BuzzRequestSigner { kind, tags, content -> NostrEvent("9".repeat(64), me, 1, kind, tags, content, "sig") }
        val result = BuzzInviteService(http, signer).mint("wss://png.example", InviteRequest(86_400, 1))
        assertEquals(InviteMintResult.Refused("招待を作れるのはオーナーと管理者だけです"), result)
    }
}
