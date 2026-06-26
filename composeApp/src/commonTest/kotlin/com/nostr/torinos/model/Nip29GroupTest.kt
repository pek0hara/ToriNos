package com.nostr.torinos.model

import com.nostr.torinos.network.nip29PublishTargets
import com.nostr.torinos.network.classifyNip29RelayError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Nip29GroupTest {
    private val publicKey = "1".repeat(64)

    @Test
    fun hFilter_serializesAsTagFilter() {
        val message = buildReqMessage("group", NostrFilter(hTags = listOf("pizza")))
        assertTrue(message.contains(""""#h":["pizza"]"""))
    }

    @Test
    fun groupRef_normalizesRelayAndUsesCompositeIdentity() {
        val first = GroupRef.create(" wss://relay.example/ ", "group")
        val second = GroupRef.create("wss://relay.example", "group")
        val other = GroupRef.create("wss://relay.example", "other")
        assertEquals(first, second)
        assertEquals("wss://relay.example|group", first.key)
        assertFalse(first == other)
    }

    @Test
    fun metadata_parsesFlagsAndSupportedKinds() {
        val event = event(
            kind = Nip29.METADATA,
            tags = listOf(
                listOf("d", "pizza"),
                listOf("name", "Pizza"),
                listOf("about", "Pizza group"),
                listOf("private"),
                listOf("restricted"),
                listOf("supported_kinds", "9", "11"),
            ),
        )
        val metadata = assertNotNull(event.toNip29Metadata())
        assertEquals("pizza", metadata.groupId)
        assertEquals("Pizza", metadata.name)
        assertTrue(metadata.isPrivate)
        assertTrue(metadata.isRestricted)
        assertEquals(setOf(9, 11), metadata.supportedKinds)
    }

    @Test
    fun metadata_parsesNameAndAboutFromJsonContent() {
        val event = event(
            kind = Nip29.METADATA,
            tags = listOf(listOf("h", "pizza"), listOf("d", "pizza")),
            content = """{"name":"Pizza","about":"Pizza group"}""",
        )
        val metadata = assertNotNull(event.toNip29Metadata())
        assertEquals("Pizza", metadata.name)
        assertEquals("Pizza group", metadata.about)
    }

    @Test
    fun creatorMetadata_parsesKind9002ForGenericRelayFallback() {
        val event = event(
            kind = Nip29.EDIT_METADATA,
            tags = listOf(
                listOf("h", "pizza"),
                listOf("name", "Pizza"),
                listOf("restricted"),
            ),
        )
        val metadata = assertNotNull(event.toNip29CreatorMetadata())
        assertEquals("pizza", metadata.groupId)
        assertEquals("Pizza", metadata.name)
        assertTrue(metadata.isRestricted)
    }

    @Test
    fun unrelatedKind39000WithoutGroupShape_isIgnored() {
        val event = event(
            kind = Nip29.METADATA,
            tags = listOf(listOf("d", "auction")),
            content = """{"item":"item name","start_price":100}""",
        )
        assertNull(event.toNip29Metadata())
    }

    @Test
    fun hiddenMetadata_isNotPubliclyDiscoverable() {
        assertTrue(Nip29GroupMetadata(groupId = "public").isPubliclyDiscoverable())
        assertFalse(Nip29GroupMetadata(groupId = "hidden", isHidden = true).isPubliclyDiscoverable())
    }

    @Test
    fun hiddenMetadata_onlyAllowsJoinedMembersToViewInfo() {
        val public = Nip29GroupMetadata(groupId = "public")
        val hidden = Nip29GroupMetadata(groupId = "hidden", isHidden = true)

        assertTrue(public.canViewInfo(Nip29Membership.NOT_JOINED))
        assertFalse(hidden.canViewInfo(Nip29Membership.NOT_JOINED))
        assertFalse(hidden.canViewInfo(Nip29Membership.PENDING))
        assertTrue(hidden.canViewInfo(Nip29Membership.JOINED))
    }

    @Test
    fun privateMetadata_onlyAllowsJoinedMembersToViewContent() {
        val public = Nip29GroupMetadata(groupId = "public")
        val private = Nip29GroupMetadata(groupId = "private", isPrivate = true)

        assertTrue(public.canViewContent(Nip29Membership.NOT_JOINED))
        assertFalse(private.canViewContent(Nip29Membership.NOT_JOINED))
        assertFalse(private.canViewContent(Nip29Membership.PENDING))
        assertTrue(private.canViewContent(Nip29Membership.JOINED))
    }

    @Test
    fun relayMetadata_requiresNip11SelfSigner() {
        val event = event(kind = Nip29.METADATA, tags = listOf(listOf("d", "pizza")))
        assertTrue(isNip29RelaySigner(event, publicKey.uppercase()))
        assertFalse(isNip29RelaySigner(event, "0".repeat(64)))
        assertFalse(isNip29RelaySigner(event, null))
    }

    @Test
    fun relaySignatureWarning_onlyReportsKnownSelfMismatch() {
        val event = event(kind = Nip29.METADATA, tags = listOf(listOf("d", "pizza")))
        assertNull(nip29RelaySignatureWarning(event, null))
        assertNull(nip29RelaySignatureWarning(event, publicKey))
        assertNotNull(nip29RelaySignatureWarning(event, "0".repeat(64)))
    }

    @Test
    fun naddr_roundTripsNip29Coordinate() {
        val encoded = encodeNaddr(
            identifier = "pizza",
            authorPubkey = publicKey,
            kind = Nip29.METADATA,
            relayUrls = listOf("wss://relay.example"),
        )
        val decoded = assertNotNull(decodeNaddr(encoded))
        assertEquals("pizza", decoded.identifier)
        assertEquals(publicKey, decoded.authorPubkey)
        assertEquals(Nip29.METADATA, decoded.kind)
        assertEquals(listOf("wss://relay.example"), decoded.relayUrls)
    }

    @Test
    fun groupList_parsesGroupTags() {
        val event = event(
            kind = Nip29.GROUP_LIST,
            tags = listOf(
                listOf("group", "pizza", "wss://relay.example/", "Pizza"),
                listOf("r", "wss://relay.example"),
            ),
        )
        val groups = parseNip29GroupList(event)
        assertEquals(1, groups.size)
        assertEquals(GroupRef.create("wss://relay.example", "pizza"), groups.single().first)
        assertEquals("Pizza", groups.single().second)
    }

    @Test
    fun chatTags_includeGroupAndPreviousReferences() {
        val others = (1..4).map { index ->
            event(
                id = index.toString().repeat(64).take(64),
                pubkey = (index + 1).toString().repeat(64).take(64),
                content = "message $index",
                kind = Nip29.CHAT_MESSAGE,
                tags = listOf(listOf("h", "pizza")),
            )
        }
        val tags = buildNip29ChatTags("pizza", others, publicKey)
        assertEquals(listOf("h", "pizza"), tags.first())
        val previous = tags.firstOrNull { it.firstOrNull() == "previous" }
        assertNotNull(previous)
        assertEquals(4, previous.size)
        assertTrue(previous.drop(1).all { it.length == 8 })
    }

    @Test
    fun previousTag_excludesOwnEvents() {
        val own = event(pubkey = publicKey, content = "own", kind = Nip29.CHAT_MESSAGE)
        assertNull(buildNip29PreviousTag(listOf(own), publicKey))
    }

    @Test
    fun membership_usesLatestPutOrRemoveUser() {
        val put = event(
            kind = Nip29.PUT_USER,
            tags = listOf(listOf("h", "pizza"), listOf("p", publicKey)),
        )
        val remove = put.copy(kind = Nip29.REMOVE_USER, createdAt = put.createdAt + 1)
        assertEquals(Nip29Membership.JOINED, determineNip29Membership(listOf(put), publicKey))
        assertEquals(Nip29Membership.NOT_JOINED, determineNip29Membership(listOf(put, remove), publicKey))
        assertEquals(
            Nip29Membership.INVITE_REQUIRED,
            determineNip29Membership(emptyList(), publicKey, rejectionMessage = "invite code required"),
        )
    }

    @Test
    fun publishTargets_onlyHostRelay() {
        val ref = GroupRef.create("wss://group.example/", "pizza")
        assertEquals(listOf("wss://group.example"), nip29PublishTargets(ref))
    }

    @Test
    fun createGroupTags_includeOnlyHostGroupContext() {
        assertEquals(
            listOf(listOf("h", "pizza"), listOf("client", "ToriNos")),
            buildNip29CreateGroupTags("pizza"),
        )
    }

    @Test
    fun deleteGroupTags_includeGroupContext() {
        assertEquals(
            listOf(
                listOf("h", "pizza"),
                listOf("client", "ToriNos"),
            ),
            buildNip29DeleteGroupTags("pizza"),
        )
    }

    @Test
    fun putUserTags_includeAdminRoleForCreator() {
        assertEquals(
            listOf(
                listOf("h", "pizza"),
                listOf("p", publicKey, "admin"),
                listOf("client", "ToriNos"),
            ),
            buildNip29PutUserTags("pizza", publicKey, listOf("admin")),
        )
    }

    @Test
    fun editMetadataTags_preserveSupportedKindsSemantics() {
        val base = Nip29GroupCreation(
            ref = GroupRef.create("wss://relay.example", "pizza"),
            name = "Pizza",
            isPrivate = true,
            isRestricted = true,
        )
        val chatTags = buildNip29EditMetadataTags(base)
        assertTrue(listOf("supported_kinds", "9") in chatTags)
        assertTrue(listOf("private") in chatTags)
        assertTrue(listOf("restricted") in chatTags)

        val allTags = buildNip29EditMetadataTags(
            base.copy(supportedKindsMode = Nip29SupportedKindsMode.ALL),
        )
        assertFalse(allTags.any { it.firstOrNull() == "supported_kinds" })

        val noneTags = buildNip29EditMetadataTags(
            base.copy(supportedKindsMode = Nip29SupportedKindsMode.NONE),
        )
        assertTrue(listOf("supported_kinds") in noneTags)
    }

    @Test
    fun relayError_isClassifiedWithoutDiscardingOriginalMessage() {
        val result = classifyNip29RelayError(
            "payment-required: 1000 sats",
            "作成できません",
        )
        assertTrue(result.contains("支払い"))
        assertTrue(result.contains("1000 sats"))
    }

    private fun event(
        id: String = "a".repeat(64),
        pubkey: String = publicKey,
        createdAt: Long = 1,
        kind: Int,
        tags: List<List<String>> = emptyList(),
        content: String = "",
    ) = NostrEvent(
        id = id,
        pubkey = pubkey,
        createdAt = createdAt,
        kind = kind,
        tags = tags,
        content = content,
        sig = "b".repeat(128),
    )
}
