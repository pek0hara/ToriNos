package com.nostr.torinos.network

import com.nostr.torinos.createHttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.isSuccess
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
    private val cacheMutex = Mutex()
    private val cache = linkedMapOf<String, LinkPreview?>()

    suspend fun fetch(url: String): LinkPreview? {
        cacheMutex.withLock {
            if (cache.containsKey(url)) return cache[url]
        }

        val preview = runCatching {
            withTimeout(5_000) {
                val response: HttpResponse = httpClient.get(url) {
                    header(HttpHeaders.Accept, "text/html,application/xhtml+xml")
                    header(HttpHeaders.UserAgent, "ToriNos/1.0 LinkPreview")
                }
                if (!response.status.isSuccess()) return@withTimeout null
                parsePreview(url, response.bodyAsText().take(MaxHtmlChars))
            }
        }.getOrNull()

        cacheMutex.withLock {
            if (cache.size >= MaxCacheEntries) {
                cache.remove(cache.keys.first())
            }
            cache[url] = preview
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
        Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }

    private fun String.attributeValue(name: String): String? =
        Regex("""\b$name\s*=\s*(["'])(.*?)\1""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
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
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        val base = runCatching { Url(baseUrl) }.getOrNull() ?: return null
        return when {
            value.startsWith("//") -> "${base.protocol.name}:$value"
            value.startsWith("/") -> "${base.protocol.name}://${base.host}$value"
            else -> null
        }
    }

    private const val MaxHtmlChars = 200_000
    private const val MaxCacheEntries = 200
}
