package com.nostr.torinos.chat

import com.nostr.torinos.model.NostrEvent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BuzzJoinServiceTest {
    private class FakeHttp : BuzzHttp {
        val requests = mutableListOf<Triple<String, String?, String?>>() // url, body, authorization
        var responses = mutableMapOf<String, BuzzHttpResponse>()
        override suspend fun get(url: String): BuzzHttpResponse {
            requests += Triple(url, null, null)
            return responses[url] ?: BuzzHttpResponse(404, "{}")
        }
        override suspend fun postJson(url: String, body: String, authorization: String?): BuzzHttpResponse {
            requests += Triple(url, body, authorization)
            return responses[url] ?: BuzzHttpResponse(404, "{}")
        }
    }

    private val signer = BuzzRequestSigner { kind, tags, content ->
        NostrEvent("sig-id", "me", 1, kind, tags, content, "sig")
    }

    private val host = "https://png.example"

    @Test
    fun policyIsFetchedFromTheCommunity() = runTest {
        val http = FakeHttp().apply {
            responses["$host/api/join-policy"] = BuzzHttpResponse(200, """{"policy":{"version":"3","terms_markdown":"規約","privacy_markdown":null,"age_attestation_required":false}}""")
        }
        val policy = BuzzJoinService(http, signer).fetchPolicy("wss://png.example")
        assertEquals("3", policy?.version)
    }

    @Test
    fun joiningWithPolicyAcceptsThenClaimsWithNip98() = runTest {
        val http = FakeHttp().apply {
            responses["$host/api/invites/accept-policy"] = BuzzHttpResponse(200, """{"receipt":"r1"}""")
            responses["$host/api/invites/claim"] = BuzzHttpResponse(200, """{"status":"joined"}""")
        }
        val result = BuzzJoinService(http, signer).claim(
            relayUrl = "wss://png.example",
            code = "v2.code",
            policy = BuzzJoinPolicy("3", "規約", null, true),
            ageConfirmed = true,
        )

        assertIs<BuzzJoinResult.Joined>(result)
        val (acceptUrl, acceptBody, acceptAuth) = http.requests[0]
        assertEquals("$host/api/invites/accept-policy", acceptUrl)
        assertEquals("""{"code":"v2.code","policy_version":"3","age_confirmed":true}""", acceptBody)
        assertNull(acceptAuth)
        val (claimUrl, claimBody, claimAuth) = http.requests[1]
        assertEquals("$host/api/invites/claim", claimUrl)
        assertEquals("""{"code":"v2.code","policy_receipt":"r1"}""", claimBody)
        assertTrue(claimAuth!!.startsWith("Nostr "))
    }

    @Test
    fun joiningWithoutPolicySkipsAcceptance() = runTest {
        val http = FakeHttp().apply {
            responses["$host/api/invites/claim"] = BuzzHttpResponse(200, """{"status":"already_member"}""")
        }
        val result = BuzzJoinService(http, signer).claim("wss://png.example", "c", policy = null, ageConfirmed = false)
        assertIs<BuzzJoinResult.Joined>(result)
        assertEquals(1, http.requests.size)
        assertEquals("""{"code":"c"}""", http.requests[0].second)
    }

    @Test
    fun refusalIsReported() = runTest {
        val http = FakeHttp().apply {
            responses["$host/api/invites/claim"] = BuzzHttpResponse(403, """{"error":"invite_expired"}""")
        }
        val result = BuzzJoinService(http, signer).claim("wss://png.example", "c", null, false)
        assertEquals(BuzzJoinResult.Refused("招待の期限が切れています。新しい招待リンクをもらってください"), result)
    }

    @Test
    fun communityHttpOriginFollowsTheRelay() {
        assertEquals("https://png.example", buzzHttpOrigin("wss://png.example"))
        assertEquals("http://localhost:3000", buzzHttpOrigin("ws://localhost:3000"))
    }
}
