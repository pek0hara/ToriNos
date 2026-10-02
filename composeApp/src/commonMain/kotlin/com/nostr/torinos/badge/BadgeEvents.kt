package com.nostr.torinos.badge

import com.nostr.torinos.model.NostrEvent

internal fun badgeHex(value: String): Boolean = value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

internal data class BadgeAddress(val kind: Int, val pubkey: String, val identifier: String) {
    val value: String get() = "$kind:$pubkey:$identifier"
    companion object {
        fun parse(value: String): BadgeAddress? {
            val parts = value.split(':', limit = 3)
            if (parts.size != 3 || !badgeHex(parts[1])) return null
            val kind = parts[0].toIntOrNull() ?: return null
            if (kind != 30009 && kind != 30008) return null
            return BadgeAddress(kind, parts[1], parts[2])
        }
    }
}

/** Raw tag blocks preserve relay hints, unsupported references and metadata during editing. */
internal data class BadgeSelectionItem(val tags: List<List<String>>) {
    val address: BadgeAddress? get() = tags.firstOrNull()?.getOrNull(1)?.let(BadgeAddress::parse)
    val awardId: String? get() = tags.getOrNull(1)?.getOrNull(1)
    val key: String get() = tags.joinToString("\u0000") { it.joinToString("\u0001") }
}

internal data class BadgeSelection(val items: List<BadgeSelectionItem>, val rawTags: List<List<String>>) {
    companion object {
        fun parse(tags: List<List<String>>): BadgeSelection {
            val items = mutableListOf<BadgeSelectionItem>()
            var index = 0
            while (index < tags.size) {
                val tag = tags[index]
                val address = if (tag.firstOrNull() == "a") tag.getOrNull(1)?.let(BadgeAddress::parse) else null
                val next = tags.getOrNull(index + 1)
                when {
                    address?.kind == 30009 && next?.firstOrNull() == "e" && badgeHex(next.getOrNull(1).orEmpty()) -> {
                        items += BadgeSelectionItem(listOf(tag, next)); index += 2
                    }
                    address?.kind == 30008 -> { items += BadgeSelectionItem(listOf(tag)); index++ }
                    else -> index++
                }
            }
            return BadgeSelection(items, tags)
        }
    }

    fun editedTags(ordered: List<BadgeSelectionItem>, migrateLegacy: Boolean = false): List<List<String>> {
        val output = mutableListOf<List<String>>()
        var index = 0
        var slot = 0
        while (index < rawTags.size) {
            val block = items.firstOrNull { item -> rawTags.drop(index).take(item.tags.size) == item.tags }
            if (block != null) {
                if (slot < ordered.size) output.addAll(ordered[slot++].tags)
                index += block.tags.size
            } else {
                val tag = rawTags[index++]
                if (!(migrateLegacy && tag.firstOrNull() == "d" && tag.getOrNull(1) == "profile_badges")) output += tag
            }
        }
        ordered.drop(slot).forEach { output.addAll(it.tags) }
        // Removing a managed block must not accidentally pair previously orphaned a/e tags.
        val expected = ordered.filter { it.address?.kind == 30009 }.map { it.tags }.toSet()
        val guarded = mutableListOf<List<String>>()
        output.forEach { tag ->
            val previous = guarded.lastOrNull()
            if (previous?.firstOrNull() == "a" && previous.getOrNull(1)?.let(BadgeAddress::parse)?.kind == 30009 &&
                tag.firstOrNull() == "e" && listOf(previous, tag) !in expected) {
                guarded += listOf("alt", "Unpaired badge reference")
            }
            guarded += tag
        }
        return guarded
    }
}

internal fun NostrEvent.isBadgeProfile(): Boolean = kind == 10008 ||
    (kind == 30008 && tags.firstOrNull { it.firstOrNull() == "d" }?.getOrNull(1) == "profile_badges")

internal fun NostrEvent.badgeAddress(): BadgeAddress? {
    if (kind != 30009 && kind != 30008) return null
    val d = tags.firstOrNull { it.firstOrNull() == "d" }?.getOrNull(1) ?: return null
    return BadgeAddress(kind, pubkey, d)
}

internal fun NostrEvent.badgeNewerThan(other: NostrEvent): Boolean =
    createdAt > other.createdAt || (createdAt == other.createdAt && id < other.id)

internal fun selectBadgeProfile(events: Collection<NostrEvent>, pubkey: String): NostrEvent? {
    val candidates = events.filter { it.pubkey == pubkey && it.isBadgeProfile() }
    val modern = candidates.filter { it.kind == 10008 }
    return (modern.ifEmpty { candidates }).sortedWith(compareByDescending<NostrEvent> { it.createdAt }.thenBy { it.id }).firstOrNull()
}

internal fun isBadgeAwardFor(event: NostrEvent, address: BadgeAddress, recipient: String): Boolean =
    event.kind == 8 && address.kind == 30009 && event.pubkey == address.pubkey &&
        event.tags.filter { it.firstOrNull() == "a" }.let { it.size == 1 && it[0].getOrNull(1) == address.value } &&
        event.tags.any { it.firstOrNull() == "p" && it.getOrNull(1) == recipient }

internal fun badgeDeleted(event: NostrEvent, deletions: Collection<NostrEvent>): Boolean = deletions.any { deletion ->
    deletion.kind == 5 && deletion.pubkey == event.pubkey && deletion.tags.any { tag ->
        (tag.firstOrNull() == "e" && tag.getOrNull(1) == event.id) ||
            (tag.firstOrNull() == "a" && tag.getOrNull(1) == event.badgeAddress()?.value && event.createdAt <= deletion.createdAt)
    }
}

/** Never mutated after creation; lets Compose skip badge rows that receive an equal item. */
@androidx.compose.runtime.Immutable
data class BadgeDisplayItem(
    val address: String,
    val awardId: String,
    val name: String,
    val description: String,
    val issuer: String,
    val recipient: String,
    val awardedAt: Long,
    val imageUrl: String?,
    val thumbnails: List<Pair<String, Int?>>,
) {
    fun thumbnailUrl(pixelSize: Int): String? = thumbnails.filter { (it.second ?: 0) >= pixelSize }
        .minByOrNull { it.second ?: Int.MAX_VALUE }?.first ?: imageUrl ?: thumbnails.firstOrNull()?.first
}

internal fun badgeDisplay(definition: NostrEvent, award: NostrEvent, recipient: String): BadgeDisplayItem {
    fun value(key: String) = definition.tags.firstOrNull { it.firstOrNull() == key }?.getOrNull(1)
    fun safeUrl(url: String?): String? = url?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    return BadgeDisplayItem(
        address = definition.badgeAddress()!!.value, awardId = award.id,
        name = value("name")?.takeIf { it.isNotBlank() } ?: value("d").orEmpty(),
        description = value("description").orEmpty(), issuer = definition.pubkey, recipient = recipient,
        awardedAt = award.createdAt, imageUrl = safeUrl(value("image")),
        thumbnails = definition.tags.filter { it.firstOrNull() == "thumb" }.mapNotNull { tag ->
            safeUrl(tag.getOrNull(1))?.let { url -> url to tag.getOrNull(2)?.substringBefore('x')?.toIntOrNull() }
        },
    )
}

internal data class BadgeStripLayout(val visibleCount: Int, val overflowCount: Int)
internal fun badgeStripWidth(layout: BadgeStripLayout, overflowWidth: Float = 24f): Float {
    val slots = layout.visibleCount + if (layout.overflowCount > 0) 1 else 0
    return layout.visibleCount * 24f + (if (layout.overflowCount > 0) overflowWidth else 0f) +
        (slots - 1).coerceAtLeast(0)
}

internal fun badgeStripLayout(count: Int, availableWidth: Float, fontScale: Float = 1f, overflowWidth: Float = 24f): BadgeStripLayout {
    val remaining = availableWidth - 96f * fontScale.coerceAtLeast(1f) - 8f
    for (visible in count.coerceIn(0, 3) downTo 0) {
        val candidate = BadgeStripLayout(visible, count - visible)
        if (badgeStripWidth(candidate, overflowWidth) <= remaining) return candidate
    }
    return BadgeStripLayout(0, 0)
}
