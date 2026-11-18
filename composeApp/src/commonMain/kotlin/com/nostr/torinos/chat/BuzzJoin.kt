package com.nostr.torinos.chat

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.network.NostrRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class BuzzHttpResponse(val status: Int, val body: String) {
    val isSuccess: Boolean get() = status in 200..299
}

/** Buzz の REST 呼び出し（テストで差し替える）。 */
interface BuzzHttp {
    suspend fun get(url: String): BuzzHttpResponse
    suspend fun postJson(url: String, body: String, authorization: String?): BuzzHttpResponse
}

internal class KtorBuzzHttp(private val client: HttpClient = NostrRepository.httpClient) : BuzzHttp {
    override suspend fun get(url: String): BuzzHttpResponse = withTimeout(REQUEST_TIMEOUT_MS) {
        val response = client.get(url) { header(HttpHeaders.Accept, "application/json") }
        BuzzHttpResponse(response.status.value, response.bodyAsText())
    }

    override suspend fun postJson(url: String, body: String, authorization: String?): BuzzHttpResponse =
        withTimeout(REQUEST_TIMEOUT_MS) {
            val response = client.post(url) {
                contentType(ContentType.Application.Json)
                authorization?.let { header(HttpHeaders.Authorization, it) }
                setBody(body)
            }
            BuzzHttpResponse(response.status.value, response.bodyAsText())
        }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 25_000L
    }
}

/** NIP-98 などに使う、その場で署名するもの。 */
fun interface BuzzRequestSigner {
    suspend fun sign(kind: Int, tags: List<List<String>>, content: String): NostrEvent
}

sealed interface BuzzJoinResult {
    data object Joined : BuzzJoinResult
    data class Refused(val message: String) : BuzzJoinResult
}

/** リレーの URL から、同じホストの REST の起点（`wss` → `https`、`ws` → `http`）。 */
fun buzzHttpOrigin(relayUrl: String): String = when {
    relayUrl.startsWith("wss://") -> "https://" + relayUrl.removePrefix("wss://").trimEnd('/')
    relayUrl.startsWith("ws://") -> "http://" + relayUrl.removePrefix("ws://").trimEnd('/')
    else -> relayUrl.trimEnd('/')
}

/**
 * Buzz コミュニティへの参加（`buzz-app` の `admitCommunity` と同じ手順）。
 * 規約があれば同意を受け取りに換え、招待コードを NIP-98 付きで使う。招待コードはログに出さない。
 */
class BuzzJoinService(private val http: BuzzHttp, private val signer: BuzzRequestSigner) {
    suspend fun fetchPolicy(relayUrl: String): BuzzJoinPolicy? {
        val response = http.get("${buzzHttpOrigin(relayUrl)}/api/join-policy")
        if (!response.isSuccess) throw IllegalStateException("参加規約を読めませんでした（${response.status}）")
        return parseBuzzJoinPolicy(response.body)
    }

    suspend fun claim(relayUrl: String, code: String, policy: BuzzJoinPolicy?, ageConfirmed: Boolean): BuzzJoinResult {
        val origin = buzzHttpOrigin(relayUrl)
        val receipt = if (policy != null) {
            val body = buildJsonObject {
                put("code", code)
                put("policy_version", policy.version)
                put("age_confirmed", ageConfirmed)
            }.toString()
            val response = http.postJson("$origin/api/invites/accept-policy", body, authorization = null)
            if (!response.isSuccess) return BuzzJoinResult.Refused(buzzRefusalMessage(response.body))
            buzzPolicyReceipt(response.body) ?: return BuzzJoinResult.Refused("規約への同意を受け付けてもらえませんでした")
        } else {
            null
        }
        val url = "$origin/api/invites/claim"
        val body = buildJsonObject {
            put("code", code)
            receipt?.let { put("policy_receipt", it) }
        }.toString()
        val auth = signer.sign(HTTP_AUTH_KIND, nip98Tags(url, "POST", body), "")
        val response = http.postJson(url, body, nostrAuthorizationHeader(auth))
        if (!response.isSuccess) return BuzzJoinResult.Refused(buzzRefusalMessage(response.body))
        return if (buzzClaimSucceeded(response.body)) BuzzJoinResult.Joined else BuzzJoinResult.Refused("参加を確認できませんでした")
    }
}
