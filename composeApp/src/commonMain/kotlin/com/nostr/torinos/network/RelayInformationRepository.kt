package com.nostr.torinos.network

import com.nostr.torinos.createHttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.isSuccess
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class RelayInformation(
    val name: String? = null,
    val description: String? = null,
    val pubkey: String? = null,
    val contact: String? = null,
    @SerialName("supported_nips")
    val supportedNips: List<Int> = emptyList(),
    val software: String? = null,
    val version: String? = null,
    val limitation: RelayLimitation? = null,
    @SerialName("posting_policy")
    val postingPolicy: String? = null,
    @SerialName("payments_url")
    val paymentsUrl: String? = null,
    @SerialName("relay_countries")
    val relayCountries: List<String> = emptyList(),
    @SerialName("language_tags")
    val languageTags: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
)

@Serializable
data class RelayLimitation(
    @SerialName("max_message_length")
    val maxMessageLength: Int? = null,
    @SerialName("max_subscriptions")
    val maxSubscriptions: Int? = null,
    @SerialName("max_filters")
    val maxFilters: Int? = null,
    @SerialName("max_limit")
    val maxLimit: Int? = null,
    @SerialName("max_subid_length")
    val maxSubIdLength: Int? = null,
    @SerialName("max_event_tags")
    val maxEventTags: Int? = null,
    @SerialName("max_content_length")
    val maxContentLength: Int? = null,
    @SerialName("min_pow_difficulty")
    val minPowDifficulty: Int? = null,
    @SerialName("auth_required")
    val authRequired: Boolean? = null,
    @SerialName("payment_required")
    val paymentRequired: Boolean? = null,
    @SerialName("restricted_writes")
    val restrictedWrites: Boolean? = null,
)

object RelayInformationRepository {
    private val httpClient = createHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = mutableMapOf<String, Result<RelayInformation>>()

    suspend fun fetch(relayUrl: String, forceRefresh: Boolean = false): Result<RelayInformation> {
        val normalizedUrl = relayUrl.trim()
        if (!forceRefresh) {
            cache[normalizedUrl]?.let { return it }
        }

        val result = runCatching {
            val informationUrl = normalizedUrl.toRelayInformationUrl()
            withTimeout(5_000) {
                val response: HttpResponse = httpClient.get(informationUrl) {
                    header(HttpHeaders.Accept, "application/nostr+json")
                    header(HttpHeaders.UserAgent, "ToriNos/1.0")
                }
                if (!response.status.isSuccess()) {
                    error("HTTP ${response.status.value}")
                }
                json.decodeFromString(RelayInformation.serializer(), response.bodyAsText())
            }
        }
        cache[normalizedUrl] = result
        return result
    }
}

private fun String.toRelayInformationUrl(): String {
    val trimmed = trim()
    val url = runCatching { Url(trimmed) }.getOrNull() ?: error("URL が正しくありません")
    when (url.protocol.name.lowercase()) {
        "wss" -> return trimmed.replace(Regex("^wss://", RegexOption.IGNORE_CASE), "https://")
        "ws" -> return trimmed.replace(Regex("^ws://", RegexOption.IGNORE_CASE), "http://")
        "https",
        "http",
        -> return trimmed
        else -> error("対応していない URL 形式です")
    }
}
