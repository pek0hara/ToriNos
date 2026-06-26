package com.nostr.torinos.model

import com.nostr.torinos.crypto.isValidEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object Nip29 {
    const val NIP_NUMBER = 29
    const val CHAT_MESSAGE = 9
    const val PUT_USER = 9000
    const val REMOVE_USER = 9001
    const val EDIT_METADATA = 9002
    const val CREATE_GROUP = 9007
    const val DELETE_GROUP = 9008
    const val JOIN_REQUEST = 9021
    const val LEAVE_REQUEST = 9022
    const val GROUP_LIST = 10009
    const val METADATA = 39000
    const val ADMINS = 39001
    const val MEMBERS = 39002
    const val ROLES = 39003
}

enum class Nip29SupportedKindsMode {
    ALL,
    TEXT_CHAT,
    NONE,
}

data class Nip29GroupCreation(
    val ref: GroupRef,
    val name: String,
    val about: String = "",
    val picture: String? = null,
    val isPrivate: Boolean = false,
    val isRestricted: Boolean = true,
    val isHidden: Boolean = false,
    val isClosed: Boolean = false,
    val supportedKindsMode: Nip29SupportedKindsMode = Nip29SupportedKindsMode.TEXT_CHAT,
)

fun buildNip29CreateGroupTags(groupId: String): List<List<String>> =
    listOf(
        listOf("h", groupId),
        listOf("client", "ToriNos"),
    )

fun buildNip29DeleteGroupTags(groupId: String): List<List<String>> =
    listOf(
        listOf("h", groupId),
        listOf("client", "ToriNos"),
    )

fun buildNip29PutUserTags(
    groupId: String,
    pubkey: String,
    roles: List<String> = emptyList(),
): List<List<String>> =
    listOf(
        listOf("h", groupId),
        listOf("p", pubkey) + roles.map(String::trim).filter { it.isNotEmpty() },
        listOf("client", "ToriNos"),
    )

fun buildNip29EditMetadataTags(creation: Nip29GroupCreation): List<List<String>> = buildList {
    add(listOf("h", creation.ref.groupId))
    add(listOf("name", creation.name.trim()))
    creation.about.trim().takeIf { it.isNotEmpty() }?.let { add(listOf("about", it)) }
    creation.picture?.trim()?.takeIf { it.isNotEmpty() }?.let { add(listOf("picture", it)) }
    if (creation.isPrivate) add(listOf("private"))
    if (creation.isRestricted) add(listOf("restricted"))
    if (creation.isHidden) add(listOf("hidden"))
    if (creation.isClosed) add(listOf("closed"))
    when (creation.supportedKindsMode) {
        Nip29SupportedKindsMode.ALL -> Unit
        Nip29SupportedKindsMode.TEXT_CHAT -> add(listOf("supported_kinds", Nip29.CHAT_MESSAGE.toString()))
        Nip29SupportedKindsMode.NONE -> add(listOf("supported_kinds"))
    }
    add(listOf("client", "ToriNos"))
}

@ConsistentCopyVisibility
data class GroupRef private constructor(
    val relayUrl: String,
    val groupId: String,
) {
    val key: String get() = "$relayUrl|$groupId"

    companion object {
        fun create(relayUrl: String, groupId: String): GroupRef {
            val normalizedRelay = normalizeRelayUrl(relayUrl)
            val normalizedId = groupId.trim()
            require(normalizedId.isNotEmpty()) { "グループIDを入力してください" }
            return GroupRef(normalizedRelay, normalizedId)
        }
    }
}

fun normalizeRelayUrl(value: String): String {
    val trimmed = value.trim()
    require(trimmed.startsWith("wss://") || trimmed.startsWith("ws://")) {
        "リレーURLはwss://またはws://で指定してください"
    }
    return trimmed.trimEnd('/')
}

data class Nip29GroupMetadata(
    val groupId: String,
    val name: String = "",
    val picture: String? = null,
    val about: String = "",
    val isPrivate: Boolean = false,
    val isRestricted: Boolean = false,
    val isHidden: Boolean = false,
    val isClosed: Boolean = false,
    /** nullはsupported_kindsタグなし（全kind対応）、空集合は対応kindなし。 */
    val supportedKinds: Set<Int>? = null,
)

fun Nip29GroupMetadata.isPubliclyDiscoverable(): Boolean = !isHidden

fun Nip29GroupMetadata.canViewInfo(membership: Nip29Membership): Boolean =
    !isHidden || membership == Nip29Membership.JOINED

fun Nip29GroupMetadata.canViewContent(membership: Nip29Membership): Boolean =
    !isPrivate || membership == Nip29Membership.JOINED

data class Nip29Admin(val pubkey: String, val roles: List<String>)
data class Nip29Role(val name: String, val description: String?)

enum class Nip29Membership {
    NOT_JOINED,
    PENDING,
    JOINED,
    REJECTED,
    INVITE_REQUIRED,
}

fun NostrEvent.toNip29Metadata(): Nip29GroupMetadata? {
    if (kind != Nip29.METADATA) return null
    return parseNip29Metadata(
        groupId = tags.firstValue("d") ?: return null,
        requireGroupShape = true,
    )
}

/**
 * NIP-29未対応の汎用リレーに保存された、作成者署名のkind 9002を
 * 公開グループ探索の互換メタデータとして解釈する。
 */
fun NostrEvent.toNip29CreatorMetadata(): Nip29GroupMetadata? {
    if (kind != Nip29.EDIT_METADATA) return null
    return parseNip29Metadata(
        groupId = tags.firstValue("h") ?: return null,
        requireGroupShape = false,
    )
}

private fun NostrEvent.parseNip29Metadata(
    groupId: String,
    requireGroupShape: Boolean,
): Nip29GroupMetadata? {
    val content = runCatching {
        nip29MetadataJson.parseToJsonElement(content).jsonObject
    }.getOrNull()
    val name = tags.firstValue("name") ?: content?.stringValue("name").orEmpty()
    val about = tags.firstValue("about") ?: content?.stringValue("about").orEmpty()
    val picture = tags.firstValue("picture") ?: content?.stringValue("picture")
    val hasGroupShape = tags.firstValue("h") == groupId ||
        name.isNotEmpty() ||
        about.isNotEmpty() ||
        picture != null ||
        tags.any { it.firstOrNull() in nip29MetadataMarkers }
    if (requireGroupShape && !hasGroupShape) return null

    val supportedTag = tags.firstOrNull { it.firstOrNull() == "supported_kinds" }
    return Nip29GroupMetadata(
        groupId = groupId,
        name = name,
        picture = picture,
        about = about,
        isPrivate = tags.hasMarker("private") || content?.booleanValue("private") == true,
        isRestricted = tags.hasMarker("restricted") || content?.booleanValue("restricted") == true,
        isHidden = tags.hasMarker("hidden") || content?.booleanValue("hidden") == true,
        isClosed = tags.hasMarker("closed") || content?.booleanValue("closed") == true,
        supportedKinds = supportedTag?.drop(1)?.mapNotNull(String::toIntOrNull)?.toSet(),
    )
}

fun NostrEvent.isValidNip29RelayMetadata(relaySelfPubkey: String?): Boolean =
    kind == Nip29.METADATA &&
        isNip29RelaySigner(this, relaySelfPubkey) &&
        isValidEvent(this)

fun isNip29RelaySigner(event: NostrEvent, relaySelfPubkey: String?): Boolean =
    relaySelfPubkey != null && relaySelfPubkey.equals(event.pubkey, ignoreCase = true)

fun nip29RelaySignatureWarning(event: NostrEvent, relaySelfPubkey: String?): String? =
    if (relaySelfPubkey != null && !relaySelfPubkey.equals(event.pubkey, ignoreCase = true)) {
        "リレーのself公開鍵と署名者が一致しません"
    } else {
        null
    }

fun NostrEvent.toNip29Admins(): List<Nip29Admin> =
    if (kind != Nip29.ADMINS) emptyList() else tags
        .filter { it.firstOrNull() == "p" }
        .mapNotNull { tag -> tag.getOrNull(1)?.let { Nip29Admin(it, tag.drop(2)) } }

fun NostrEvent.toNip29Members(): List<String> =
    if (kind != Nip29.MEMBERS) emptyList() else tags
        .filter { it.firstOrNull() == "p" }
        .mapNotNull { it.getOrNull(1) }

fun NostrEvent.toNip29Roles(): List<Nip29Role> =
    if (kind != Nip29.ROLES) emptyList() else tags
        .filter { it.firstOrNull() == "role" }
        .mapNotNull { tag -> tag.getOrNull(1)?.let { Nip29Role(it, tag.getOrNull(2)) } }

fun parseNip29GroupList(event: NostrEvent): List<Pair<GroupRef, String?>> =
    if (event.kind != Nip29.GROUP_LIST) emptyList() else event.tags
        .filter { it.firstOrNull() == "group" }
        .mapNotNull { tag ->
            runCatching {
                GroupRef.create(
                    relayUrl = tag.getOrNull(2).orEmpty(),
                    groupId = tag.getOrNull(1).orEmpty(),
                ) to tag.getOrNull(3)
            }.getOrNull()
        }

fun buildNip29PreviousTag(
    recentEvents: List<NostrEvent>,
    ownPubkey: String,
    maxReferences: Int = 3,
): List<String>? {
    val refs = recentEvents
        .asSequence()
        .filter { it.pubkey != ownPubkey }
        .distinctBy { it.id }
        .take(50)
        .take(maxReferences)
        .map { it.id.take(8) }
        .toList()
    return refs.takeIf { it.isNotEmpty() }?.let { listOf("previous") + it }
}

fun determineNip29Membership(
    events: List<NostrEvent>,
    ownPubkey: String,
    pending: Boolean = false,
    rejectionMessage: String? = null,
): Nip29Membership {
    val latest = events
        .filter { it.kind == Nip29.PUT_USER || it.kind == Nip29.REMOVE_USER }
        .filter { event -> event.tags.any { it.firstOrNull() == "p" && it.getOrNull(1) == ownPubkey } }
        .maxByOrNull { it.createdAt }
    if (latest?.kind == Nip29.PUT_USER) return Nip29Membership.JOINED
    if (latest?.kind == Nip29.REMOVE_USER) return Nip29Membership.NOT_JOINED
    if (pending) return Nip29Membership.PENDING
    val message = rejectionMessage.orEmpty().lowercase()
    return when {
        "invite" in message || "code" in message -> Nip29Membership.INVITE_REQUIRED
        rejectionMessage != null -> Nip29Membership.REJECTED
        else -> Nip29Membership.NOT_JOINED
    }
}

fun buildNip29ChatTags(
    groupId: String,
    recentEvents: List<NostrEvent>,
    ownPubkey: String,
): List<List<String>> = buildList {
    add(listOf("h", groupId))
    buildNip29PreviousTag(recentEvents, ownPubkey)?.let(::add)
    add(listOf("client", "ToriNos"))
}

private fun List<List<String>>.firstValue(name: String): String? =
    firstOrNull { it.firstOrNull() == name }?.getOrNull(1)

private val nip29MetadataJson = Json { ignoreUnknownKeys = true }
private val nip29MetadataMarkers = setOf(
    "private",
    "restricted",
    "hidden",
    "closed",
    "supported_kinds",
)

private fun kotlinx.serialization.json.JsonObject.stringValue(name: String): String? =
    get(name)?.jsonPrimitive?.content

private fun kotlinx.serialization.json.JsonObject.booleanValue(name: String): Boolean? =
    get(name)?.jsonPrimitive?.booleanOrNull

private fun List<List<String>>.hasMarker(name: String): Boolean =
    any { it.firstOrNull() == name }
