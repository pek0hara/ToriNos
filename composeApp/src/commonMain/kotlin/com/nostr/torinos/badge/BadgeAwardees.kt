package com.nostr.torinos.badge

import com.nostr.torinos.model.NostrEvent

/** Upper bound of awards read for one badge's recipient list; reaching it means the list may be cut short. */
internal const val BADGE_AWARDEES_LIMIT = 500

/** One recipient of a badge, with the first award that gave it to them. */
internal data class BadgeAwardee(val pubkey: String, val awardedAt: Long, val awardId: String)

/**
 * [complete] means every relay answered and the award limit was not reached; [truncated] that the
 * limit was reached, so older recipients may be missing.
 */
internal data class BadgeAwardeesResult(
    val awardees: List<BadgeAwardee>,
    val complete: Boolean,
    val truncated: Boolean = false,
)

/**
 * Recipients of [address] from kind 8 awards signed by its issuer, newest first. Awards with more
 * than one `a` tag, or deleted by the issuer through kind 5 in [events], are ignored. [known] is an
 * award already verified elsewhere (the one being viewed) and is kept even if relays miss it.
 */
internal fun badgeAwardees(address: BadgeAddress, events: Collection<NostrEvent>, known: BadgeAwardee? = null): List<BadgeAwardee> {
    val deletions = events.filter { it.kind == 5 && it.pubkey == address.pubkey }
    val first = mutableMapOf<String, BadgeAwardee>()
    fun add(awardee: BadgeAwardee) {
        val existing = first[awardee.pubkey]
        if (existing == null || awardee.awardedAt < existing.awardedAt ||
            (awardee.awardedAt == existing.awardedAt && awardee.awardId < existing.awardId)) first[awardee.pubkey] = awardee
    }
    events.distinctBy { it.id }.forEach { award ->
        if (award.kind != 8 || award.pubkey != address.pubkey) return@forEach
        if (award.tags.filter { it.firstOrNull() == "a" }.singleOrNull()?.getOrNull(1) != address.value) return@forEach
        if (badgeDeleted(award, deletions)) return@forEach
        award.tags.filter { it.firstOrNull() == "p" }.mapNotNull { it.getOrNull(1) }.filter(::badgeHex).distinct()
            .forEach { add(BadgeAwardee(it, award.createdAt, award.id)) }
    }
    known?.takeIf { awardee -> badgeHex(awardee.pubkey) &&
        deletions.none { deletion -> deletion.tags.any { it.firstOrNull() == "e" && it.getOrNull(1) == awardee.awardId } } }?.let(::add)
    return first.values.sortedWith(compareByDescending<BadgeAwardee> { it.awardedAt }.thenBy { it.pubkey })
}
