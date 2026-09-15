package com.nostr.torinos.model

import kotlinx.serialization.Serializable

/** NIP-94 file metadata shared by upload, posting, and rendering. */
@Serializable
data class MediaMetadata(
    val url: String,
    val mimeType: String? = null,
    val sha256: String? = null,
    val originalSha256: String? = null,
    val sizeBytes: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val blurhash: String? = null,
    val thumbnailUrl: String? = null,
    val previewUrl: String? = null,
    val alt: String? = null,
    val fallbackUrls: List<String> = emptyList(),
    val service: String? = null,
) {
    val isImage: Boolean
        get() = mimeType?.startsWith("image/", ignoreCase = true) == true || looksLikeImageUrl(url)

    /** NIP-92 representation for embedding NIP-94 fields in a note. */
    fun toImetaTag(): List<String> = buildList {
        add("imeta")
        add("url $url")
        mimeType?.takeIf { it.isNotBlank() }?.let { add("m ${it.lowercase()}") }
        sha256?.takeIf { it.isNotBlank() }?.let { add("x $it") }
        originalSha256?.takeIf { it.isNotBlank() }?.let { add("ox $it") }
        sizeBytes?.let { add("size $it") }
        if (width != null && height != null) add("dim ${width}x$height")
        blurhash?.takeIf { it.isNotBlank() }?.let { add("blurhash $it") }
        thumbnailUrl?.takeIf { it.isNotBlank() }?.let { add("thumb $it") }
        previewUrl?.takeIf { it.isNotBlank() }?.let { add("image $it") }
        alt?.takeIf { it.isNotBlank() }?.let { add("alt $it") }
        fallbackUrls.filter { it.isNotBlank() }.forEach { add("fallback $it") }
        service?.takeIf { it.isNotBlank() }?.let { add("service $it") }
    }
}

fun parseImetaTags(tags: List<List<String>>): List<MediaMetadata> =
    tags.asSequence()
        .filter { it.firstOrNull() == "imeta" }
        .mapNotNull { parseNip94Entries(it.drop(1)) }
        .toList()

fun parseNip94Event(event: NostrEvent): MediaMetadata? =
    if (event.kind == NIP94_FILE_METADATA_KIND) {
        parseNip94Entries(event.tags.mapNotNull { tag ->
            val key = tag.firstOrNull() ?: return@mapNotNull null
            val value = tag.getOrNull(1) ?: return@mapNotNull null
            "$key $value"
        })
    } else {
        null
    }

fun parseNip94Entries(entries: List<String>): MediaMetadata? {
    val fields = entries.mapNotNull { entry ->
        val separator = entry.indexOfFirst(Char::isWhitespace)
        if (separator <= 0) return@mapNotNull null
        val key = entry.substring(0, separator)
        val value = entry.substring(separator + 1).trim()
        if (value.isBlank()) null else key to value
    }
    val url = fields.firstOrNull { it.first == "url" }?.second ?: return null
    val dimensions = fields.firstOrNull { it.first == "dim" }?.second?.parseDimensions()
    return MediaMetadata(
        url = url,
        mimeType = fields.firstValue("m"),
        sha256 = fields.firstValue("x"),
        originalSha256 = fields.firstValue("ox"),
        sizeBytes = fields.firstValue("size")?.toLongOrNull()?.takeIf { it >= 0 },
        width = dimensions?.first,
        height = dimensions?.second,
        blurhash = fields.firstValue("blurhash"),
        thumbnailUrl = fields.firstValue("thumb"),
        previewUrl = fields.firstValue("image"),
        alt = fields.firstValue("alt"),
        fallbackUrls = fields.filter { it.first == "fallback" }.map { it.second },
        service = fields.firstValue("service"),
    )
}

/** Small image URL for a timeline; the original URL remains the open/download target. */
fun MediaMetadata.timelinePreviewUrl(widthPx: Int = 720): String {
    thumbnailUrl?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { return it }
    imgurThumbnailUrl(url)?.let { return it }
    blossomThumbnailUrl(url, widthPx)?.let { return it }
    return url
}

private fun List<Pair<String, String>>.firstValue(key: String): String? =
    firstOrNull { it.first == key }?.second

private fun String.parseDimensions(): Pair<Int, Int>? {
    val parts = lowercase().split('x', limit = 2)
    val width = parts.getOrNull(0)?.toIntOrNull()?.takeIf { it > 0 } ?: return null
    val height = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 } ?: return null
    return width to height
}

private fun looksLikeImageUrl(url: String): Boolean =
    IMAGE_EXTENSION_REGEX.containsMatchIn(url.substringBefore('?').substringBefore('#'))

private fun imgurThumbnailUrl(url: String): String? {
    val match = IMGUR_IMAGE_REGEX.matchEntire(url) ?: return null
    return "${match.groupValues[1]}${match.groupValues[2]}m.${match.groupValues[3]}${match.groupValues[4]}"
}

private fun blossomThumbnailUrl(url: String, widthPx: Int): String? {
    val host = URL_HOST_REGEX.find(url)?.groupValues?.getOrNull(1)?.lowercase() ?: return null
    if (host != "blossom.band" && !host.endsWith(".blossom.band")) return null
    val separator = if ('?' in url) '&' else '?'
    return "$url${separator}w=${widthPx.coerceIn(1, 1080)}"
}

const val NIP94_FILE_METADATA_KIND = 1063

private val IMAGE_EXTENSION_REGEX = Regex("\\.(?:jpg|jpeg|png|gif|webp|bmp|svg)$", RegexOption.IGNORE_CASE)
private val IMGUR_IMAGE_REGEX = Regex(
    "^(https?://i\\.imgur\\.com/)([A-Za-z0-9]+)\\.(jpg|jpeg|png|gif|webp)([?#].*)?$",
    RegexOption.IGNORE_CASE,
)
private val URL_HOST_REGEX = Regex("^https?://([^/:?#]+)", RegexOption.IGNORE_CASE)
