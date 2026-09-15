package com.nostr.torinos.network

import com.nostr.torinos.createHttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object YouTubePreviewRepository {
    private val httpClient = createHttpClient()
    private val fetchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cacheMutex = Mutex()
    private val cache = linkedMapOf<String, String?>()
    private val inFlight = mutableMapOf<String, Deferred<String?>>()

    suspend fun fetchTitle(videoId: String): String? {
        val deferred = cacheMutex.withLock {
            if (cache.containsKey(videoId)) return cache[videoId]
            inFlight.getOrPut(videoId) { fetchScope.async { doFetchTitle(videoId) } }
        }
        return deferred.await()
    }

    private suspend fun doFetchTitle(videoId: String): String? {
        val title = runCatching {
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

        cacheMutex.withLock {
            if (cache.size >= MaxCacheEntries) cache.remove(cache.keys.first())
            cache[videoId] = title
            inFlight.remove(videoId)
        }
        return title
    }

    private const val YouTubeOEmbedEndpoint = "https://www.youtube.com/oembed"
    private const val MaxCacheEntries = 200
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
