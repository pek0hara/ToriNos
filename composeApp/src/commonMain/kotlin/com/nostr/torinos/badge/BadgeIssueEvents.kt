package com.nostr.torinos.badge

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.decodeNpub
import com.nostr.torinos.network.parseNip05Address

internal const val BADGE_NAME_MAX = 64
internal const val BADGE_DESCRIPTION_MAX = 500
internal const val BADGE_IDENTIFIER_MAX = 64
internal const val BADGE_AWARD_RECIPIENTS_MAX = 50
/** References per deletion request; larger requests are split so relays do not reject them. */
internal const val BADGE_DELETION_CHUNK = 100

private val clientTag = listOf("client", "ToriNos")
private val identifierPattern = Regex("[A-Za-z0-9_-]{1,$BADGE_IDENTIFIER_MAX}")

/** Editable fields of a kind 30009 definition; [baseline] keeps unknown tags and content of the edited version. */
internal data class BadgeDefinitionDraft(
    val identifier: String,
    val name: String,
    val description: String = "",
    val imageUrl: String? = null,
    val imageSize: String? = null,
    val thumbUrl: String? = null,
    val thumbSize: String? = null,
    val baseline: NostrEvent? = null,
) {
    companion object {
        fun from(definition: NostrEvent): BadgeDefinitionDraft {
            fun tag(key: String) = definition.tags.firstOrNull { it.firstOrNull() == key }
            return BadgeDefinitionDraft(
                identifier = tag("d")?.getOrNull(1).orEmpty(),
                name = tag("name")?.getOrNull(1).orEmpty(),
                description = tag("description")?.getOrNull(1).orEmpty(),
                imageUrl = tag("image")?.getOrNull(1), imageSize = tag("image")?.getOrNull(2),
                thumbUrl = tag("thumb")?.getOrNull(1), thumbSize = tag("thumb")?.getOrNull(2),
                baseline = definition,
            )
        }
    }
}

internal fun isValidBadgeIdentifier(value: String): Boolean = identifierPattern.matches(value)

/** A readable identifier from the name, unique among [taken] (which should include deleted identifiers). */
internal fun badgeIdentifierFrom(name: String, taken: Set<String>): String {
    val slug = name.lowercase().map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }.joinToString("")
        .replace(Regex("-+"), "-").trim('-').take(BADGE_IDENTIFIER_MAX - 8).ifEmpty { "badge" }
    if (slug !in taken) return slug
    var index = 2
    while ("$slug-$index" in taken) index++
    return "$slug-$index"
}

private fun isHttpUrl(value: String) = value.startsWith("https://") || value.startsWith("http://")

/** Null when the draft can be signed; otherwise the message to show. */
internal fun validateBadgeDefinition(draft: BadgeDefinitionDraft, takenIdentifiers: Set<String>): String? = when {
    !isValidBadgeIdentifier(draft.identifier) -> "識別子は英数字・-・_ の${BADGE_IDENTIFIER_MAX}文字以内で入力してください"
    draft.baseline == null && draft.identifier in takenIdentifiers -> "この識別子は使用済みです"
    draft.baseline != null && draft.baseline.badgeAddress()?.identifier != draft.identifier -> "識別子は変更できません"
    draft.name.isBlank() -> "名前を入力してください"
    draft.name.length > BADGE_NAME_MAX -> "名前は${BADGE_NAME_MAX}文字以内で入力してください"
    draft.description.length > BADGE_DESCRIPTION_MAX -> "説明は${BADGE_DESCRIPTION_MAX}文字以内で入力してください"
    draft.imageUrl != null && !isHttpUrl(draft.imageUrl) -> "画像のURLが正しくありません"
    draft.thumbUrl != null && !isHttpUrl(draft.thumbUrl) -> "サムネイルのURLが正しくありません"
    else -> null
}

/** Tags of a kind 30009 event. Fields edited here are replaced; every other tag of the baseline is kept. */
internal fun badgeDefinitionTags(draft: BadgeDefinitionDraft): List<List<String>> {
    val managed = setOf("d", "name", "description", "image", "thumb", "client")
    return buildList {
        add(listOf("d", draft.identifier))
        add(listOf("name", draft.name.trim()))
        draft.description.trim().takeIf { it.isNotEmpty() }?.let { add(listOf("description", it)) }
        draft.imageUrl?.let { add(listOfNotNull("image", it, draft.imageSize)) }
        // Other clients may publish several sizes; keep them all while the primary thumbnail is unchanged.
        val baselineThumbs = draft.baseline?.tags?.filter { it.firstOrNull() == "thumb" }.orEmpty()
        if (draft.thumbUrl != null && baselineThumbs.firstOrNull()?.getOrNull(1) == draft.thumbUrl) addAll(baselineThumbs)
        else draft.thumbUrl?.let { add(listOfNotNull("thumb", it, draft.thumbSize)) }
        draft.baseline?.tags?.filter { it.firstOrNull() !in managed }?.let(::addAll)
        add(clientTag)
    }
}

/** One award per recipient, so a single recipient can be revoked without touching the others. */
internal fun badgeAwardTags(address: BadgeAddress, recipient: String, relayHint: String? = null): List<List<String>> {
    require(address.kind == 30009 && badgeHex(recipient))
    return listOf(listOf("a", address.value), listOfNotNull("p", recipient, relayHint), clientTag)
}

/**
 * Deletion request tags for own definitions and awards, split into chunks of [BADGE_DELETION_CHUNK]
 * references. A definition is referenced by address (all versions up to the request) and by its id.
 */
internal fun badgeDeletionTags(targets: List<NostrEvent>): List<List<List<String>>> {
    val references = targets.distinctBy { it.id }.flatMap { event ->
        val address = event.badgeAddress()?.takeIf { event.kind == 30009 }
        listOfNotNull(address?.let { listOf("a", it.value) to event.kind }, listOf("e", event.id) to event.kind)
    }
    return references.chunked(BADGE_DELETION_CHUNK).map { chunk ->
        chunk.map { it.first } + chunk.map { it.second }.distinct().sorted().map { listOf("k", it.toString()) } + listOf(clientTag)
    }
}

internal sealed interface BadgeRecipientInput {
    data class Pubkey(val hex: String) : BadgeRecipientInput
    data class Nip05(val address: String) : BadgeRecipientInput
    data object Invalid : BadgeRecipientInput
}

internal fun parseBadgeRecipient(value: String): BadgeRecipientInput {
    val text = value.trim().removePrefix("nostr:")
    return when {
        badgeHex(text.lowercase()) -> BadgeRecipientInput.Pubkey(text.lowercase())
        text.startsWith("npub1") -> decodeNpub(text)?.let { BadgeRecipientInput.Pubkey(it) } ?: BadgeRecipientInput.Invalid
        parseNip05Address(text.lowercase()) != null -> BadgeRecipientInput.Nip05(text.lowercase())
        else -> BadgeRecipientInput.Invalid
    }
}

/**
 * Follows whose display name, name or NIP-05 contains [query] (case-insensitive). Names that start
 * with the query come first, then alphabetical; follows without a loaded profile never match.
 */
internal fun searchFollows(
    query: String,
    follows: Collection<String>,
    profiles: Map<String, com.nostr.torinos.model.NostrProfile>,
    limit: Int = Int.MAX_VALUE,
): List<String> {
    val needle = query.trim().lowercase()
    fun fields(pubkey: String) = profiles[pubkey]?.let { listOfNotNull(it.displayName, it.name, it.nip05) }.orEmpty()
        .map { it.lowercase() }.filter { it.isNotBlank() }
    return follows.mapNotNull { pubkey ->
        val values = fields(pubkey)
        val rank = when {
            needle.isEmpty() -> 1
            values.any { it.startsWith(needle) } -> 0
            values.any { needle in it } -> 1
            else -> return@mapNotNull null
        }
        Triple(pubkey, rank, profiles[pubkey]?.bestName?.lowercase() ?: "￿$pubkey")
    }.sortedWith(compareBy({ it.second }, { it.third })).take(limit).map { it.first }
}
