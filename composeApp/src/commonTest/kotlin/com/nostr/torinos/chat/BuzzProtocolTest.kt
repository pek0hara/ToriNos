package com.nostr.torinos.chat

import com.nostr.torinos.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BuzzProtocolTest {
    @Test
    fun joinPolicyIsReadOrAbsent() {
        assertEquals(
            BuzzJoinPolicy("1", "# 規約", null, true),
            parseBuzzJoinPolicy("""{"policy":{"version":"1","terms_markdown":"# 規約","privacy_markdown":null,"age_attestation_required":true}}"""),
        )
        assertNull(parseBuzzJoinPolicy("{}"))
        assertNull(parseBuzzJoinPolicy("""{"policy":null}"""))
    }

    @Test
    fun malformedJoinPolicyIsAnErrorNotAbsent() {
        assertFailsWith<IllegalArgumentException> { parseBuzzJoinPolicy("""{"policy":{"version":1}}""") }
        assertFailsWith<IllegalArgumentException> { parseBuzzJoinPolicy("[]") }
    }

    @Test
    fun refusalCodesBecomeMessages() {
        assertEquals("招待の期限が切れています。新しい招待リンクをもらってください", buzzRefusalMessage("""{"error":"invite_expired"}"""))
        assertEquals("招待の利用回数の上限に達しています", buzzRefusalMessage("""{"error":"invite_exhausted"}"""))
        assertEquals("このコミュニティでは使えない招待です", buzzRefusalMessage("""{"error":"invite_invalid"}"""))
        assertEquals("規約が更新されました。もう一度確認してください", buzzRefusalMessage("""{"error":"join_policy_not_accepted"}"""))
        assertEquals("参加できませんでした（other）", buzzRefusalMessage("""{"error":"other"}"""))
        assertEquals("参加できませんでした", buzzRefusalMessage("not json"))
    }

    @Test
    fun claimStatusIsChecked() {
        assertEquals(true, buzzClaimSucceeded("""{"status":"joined"}"""))
        assertEquals(true, buzzClaimSucceeded("""{"status":"already_member"}"""))
        assertEquals(false, buzzClaimSucceeded("""{"status":"pending"}"""))
    }

    @Test
    fun nip98TagsBindUrlMethodAndPayload() {
        val tags = nip98Tags("https://h.example/api/invites/claim", "POST", """{"code":"x"}""")
        assertEquals(listOf("u", "https://h.example/api/invites/claim"), tags[0])
        assertEquals(listOf("method", "POST"), tags[1])
        assertEquals("payload", tags[2][0])
        assertEquals(64, tags[2][1].length)
    }

    @Test
    fun authorizationHeaderIsBase64OfEventJson() {
        val event = NostrEvent("id", "pk", 1, 27235, listOf(listOf("u", "x")), "", "sig")
        val header = nostrAuthorizationHeader(event)
        assertTrue(header.startsWith("Nostr "))
        assertTrue(decodeBase64ForTest(header.removePrefix("Nostr ")).contains("\"kind\":27235"))
    }

    @Test
    fun blossomReadProofIsScopedToTheServer() {
        val tags = blossomGetTags(host = "png.communities.buzz.xyz", now = 1000, ttlSeconds = 55)
        assertEquals(
            listOf(listOf("t", "get"), listOf("expiration", "1055"), listOf("server", "png.communities.buzz.xyz")),
            tags,
        )
    }

    @Test
    fun blossomTokenIsReusedUntilShortlyBeforeExpiry() {
        val cache = BlossomTokenCache(refreshMarginSeconds = 5)
        cache.put("h", "Nostr a", expiresAt = 100)
        assertEquals("Nostr a", cache.get("h", now = 90))
        assertNull(cache.get("h", now = 96))
        assertNull(cache.get("other", now = 0))
    }

    @Test
    fun mediaUrlOnCommunityHostNeedsAuth() {
        assertEquals("png.communities.buzz.xyz", buzzMediaHost("https://png.communities.buzz.xyz/media/abc.png", setOf("wss://png.communities.buzz.xyz")))
        assertNull(buzzMediaHost("https://other.example/media/abc.png", setOf("wss://png.communities.buzz.xyz")))
        assertNull(buzzMediaHost("https://png.communities.buzz.xyz/other/abc.png", setOf("wss://png.communities.buzz.xyz")))
    }
}

private fun decodeBase64ForTest(value: String): String =
    kotlin.io.encoding.Base64.decode(value).decodeToString()

class BuzzUploadTest {
    @Test
    fun uploadProofIsScopedToTheBlob() {
        assertEquals(
            listOf(listOf("t", "upload"), listOf("x", "ab".repeat(32)), listOf("expiration", "1055"), listOf("server", "h.example")),
            blossomUploadTags("h.example", "ab".repeat(32), now = 1000, ttlSeconds = 55),
        )
    }

    @Test
    fun blobDescriptorIsReadAndMustStayOnTheCommunity() {
        val body = """{"url":"https://h.example/media/${"ab".repeat(32)}.png","sha256":"${"ab".repeat(32)}","size":1234,"type":"image/png","uploaded":1,"dim":"640x480"}"""
        val blob = parseBuzzBlob(body, "https://h.example")!!
        assertEquals(1234L, blob.size)
        assertEquals("640x480", blob.dim)
        assertNull(parseBuzzBlob(body.replace("https://h.example", "https://evil.example"), "https://h.example"))
        assertNull(parseBuzzBlob("""{"error":"x"}""", "https://h.example"))
    }

    @Test
    fun attachmentBecomesMarkdownAndImeta() {
        val blob = BuzzBlob("https://h.example/media/a.png", "aa", 10, "image/png", "2x3")
        val attachment = buzzAttachment(blob, "写真 [1].png")
        assertEquals("![写真 \\[1\\].png](<https://h.example/media/a.png>)", attachment.markdown)
        assertEquals(
            listOf("imeta", "url https://h.example/media/a.png", "m image/png", "size 10", "x aa", "filename 写真 [1].png", "dim 2x3"),
            attachment.imeta,
        )
    }

    @Test
    fun nonImageAttachmentIsALink() {
        val blob = BuzzBlob("https://h.example/media/a.pdf", "aa", 10, "application/pdf", null)
        assertEquals("[doc.pdf](<https://h.example/media/a.pdf>)", buzzAttachment(blob, "doc.pdf").markdown)
    }

    @Test
    fun attachmentsAreAppendedToTheDraft() {
        val draft = GroupEventDraft(9, listOf(listOf("h", "g")), "本文")
        val withAttachment = draft.withAttachments(listOf(MessageAttachment("![a](<u>)", listOf("imeta", "url u"))))
        assertEquals("本文\n\n![a](<u>)", withAttachment.content)
        assertEquals(listOf(listOf("h", "g"), listOf("imeta", "url u")), withAttachment.tags)
        assertEquals(draft, draft.withAttachments(emptyList()))
    }

    @Test
    fun uploadSendsTheHashHeaderBuzzRequires() {
        val hash = "a".repeat(64)
        val headers = buzzUploadHeaders("Nostr abc", hash)
        // 無いと Buzz は 401(authentication failed)を返す。
        assertEquals(hash, headers["X-SHA-256"])
        assertEquals("Nostr abc", headers["Authorization"])
    }
}
