package com.nostr.torinos.network

import com.nostr.torinos.createHttpClient
import com.nostr.torinos.util.TtlFetchCache
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object YouTubePreviewRepository {
    private val httpClient = createHttpClient()
    private val fetchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cache = TtlFetchCache<String, String>(
        scope = fetchScope,
        maximumSize = MaxCacheEntries,
        successTtlMillis = SuccessTtlMillis,
        failureTtlMillis = FailureTtlMillis,
        name = "YouTubePreviewRepository",
    )

    suspend fun fetchTitle(videoId: String): String? = cache.get(videoId) { doFetchTitle(videoId) }

    private suspend fun doFetchTitle(videoId: String): String? = runCatching {
        withTimeout(5_000) {
            val response = httpClient.get(YouTubeOEmbedEndpoint) {
                parameter("url", "https://www.youtube.com/watch?v=$videoId")
                parameter("format", "json")
                header(HttpHeaders.Accept, "application/json")
                header(HttpHeaders.UserAgent, "ToriNos/1.0 YouTubePreview")
            }
            if (!response.status.isSuccess()) return@withTimeout null
            parseYouTubeOEmbedTitle(response.bodyAsText())
        }
    }.getOrNull()

    private const val YouTubeOEmbedEndpoint = "https://www.youtube.com/oembed"
    private const val MaxCacheEntries = 200
    private const val SuccessTtlMillis = 60 * 60 * 1_000L
    private const val FailureTtlMillis = 2 * 60 * 1_000L
}

internal fun parseYouTubeOEmbedTitle(body: String): String? =
    runCatching {
        Json.parseToJsonElement(body)
            .jsonObject["title"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }.getOrNull()
