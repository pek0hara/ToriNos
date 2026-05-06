package com.nostr.torinos.network

import com.nostr.torinos.createHttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

data class LinkPreview(
    val url: String,
    val title: String,
    val description: String? = null,
    val imageUrl: String? = null,
    val siteName: String? = null,
)

object LinkPreviewRepository {
    private val httpClient = createHttpClient()
    private val fetchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cacheMutex = Mutex()
    private val cache = linkedMapOf<String, LinkPreview?>()
    // 同じ URL への並行フェッチを1本に束ねる
    private val inFlight = mutableMapOf<String, Deferred<LinkPreview?>>()

    suspend fun fetch(url: String): LinkPreview? {
        if (!isSafeLinkPreviewUrl(url)) return null
        val deferred: Deferred<LinkPreview?> = cacheMutex.withLock {
            if (cache.containsKey(url)) return cache[url]
            inFlight.getOrPut(url) { fetchScope.async { doFetch(url) } }
        }
        return deferred.await()
    }

    private suspend fun doFetch(url: String): LinkPreview? {
        val preview = runCatching {
            withTimeout(5_000) {
                val response: HttpResponse = httpClient.get(url) {
                    header(HttpHeaders.Accept, "text/html,application/xhtml+xml")
                    header(HttpHeaders.UserAgent, "ToriNos/1.0 LinkPreview")
                }
                if (!response.status.isSuccess()) return@withTimeout null
                if (!isSafeLinkPreviewUrl(response.request.url.toString())) return@withTimeout null
                parsePreview(url, response.bodyAsText().take(MaxHtmlChars))
            }
        }.getOrNull()
        cacheMutex.withLock {
            if (cache.size >= MaxCacheEntries) cache.remove(cache.keys.first())
            cache[url] = preview
            inFlight.remove(url)
        }
        return preview
    }

    private fun parsePreview(url: String, html: String): LinkPreview? {
        val title = metaContent(html, "property", "og:title")
            ?: metaContent(html, "name", "twitter:title")
            ?: htmlTitle(html)
            ?: return null
        val description = metaContent(html, "property", "og:description")
            ?: metaContent(html, "name", "description")
            ?: metaContent(html, "name", "twitter:description")
        val imageUrl = metaContent(html, "property", "og:image")
            ?: metaContent(html, "name", "twitter:image")
        val siteName = metaContent(html, "property", "og:site_name")
            ?: runCatching { Url(url).host }.getOrNull()

        return LinkPreview(
            url = url,
            title = title.cleanPreviewText(),
            description = description?.cleanPreviewText()?.takeIf { it.isNotBlank() },
            imageUrl = imageUrl?.resolveAgainst(url),
            siteName = siteName?.cleanPreviewText()?.takeIf { it.isNotBlank() },
        )
    }

    private fun metaContent(html: String, keyAttribute: String, keyValue: String): String? {
        val metaTag = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.value }
            .firstOrNull { tag ->
                tag.attributeValue(keyAttribute)?.equals(keyValue, ignoreCase = true) == true
            }
            ?: return null
        return metaTag.attributeValue("content")?.takeIf { it.isNotBlank() }
    }

    private fun htmlTitle(html: String): String? =
        Regex("""<title[^>]*>([\s\S]*?)</title>""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }

    private fun String.attributeValue(name: String): String? =
        Regex("""\b$name\s*=\s*(["'])(.*?)\1""", RegexOption.IGNORE_CASE)
            .find(this)
            ?.groupValues
            ?.getOrNull(2)
            ?.htmlDecode()

    private fun String.cleanPreviewText(): String =
        htmlDecode()
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun String.htmlDecode(): String =
        replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")

    private fun String.resolveAgainst(baseUrl: String): String? {
        val value = trim()
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return value.takeIf(::isSafeLinkPreviewUrl)
        }
        val base = runCatching { Url(baseUrl) }.getOrNull() ?: return null
        return when {
            value.startsWith("//") -> "${base.protocol.name}:$value"
            value.startsWith("/") -> "${base.protocol.name}://${base.host}$value"
            else -> null
        }?.takeIf(::isSafeLinkPreviewUrl)
    }

    private const val MaxHtmlChars = 200_000
    private const val MaxCacheEntries = 200
}

internal fun isSafeLinkPreviewUrl(value: String): Boolean {
    val url = runCatching { Url(value) }.getOrNull() ?: return false
    if (url.protocol.name != "https") return false

    val host = url.host.trim().lowercase().trimEnd('.')
    if (host.isBlank()) return false
    if (host == "localhost" || host.endsWith(".localhost")) return false
    if (host.contains(":")) return false
    if (host.isPrivateIpv4Literal()) return false
    if (host.endsWith(".local") || host.endsWith(".internal")) return false
    return true
}

private fun String.isPrivateIpv4Literal(): Boolean {
    val parts = split(".")
    if (parts.size != 4) return false
    val octets = parts.map { it.toIntOrNull() ?: return false }
    if (octets.any { it !in 0..255 }) return false
    val first = octets[0]
    val second = octets[1]
    return first == 0 ||
        first == 10 ||
        first == 127 ||
        first == 169 && second == 254 ||
        first == 172 && second in 16..31 ||
        first == 192 && second == 168 ||
        first >= 224
}
