package com.nostr.torinos.chat

import com.nostr.torinos.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupStateTest {
    private val relayKey = "c".repeat(64)
    private val admin = "d".repeat(64)
    private val me = "a".repeat(64)
    private val other = "b".repeat(64)

    private fun event(
        kind: Int,
        tags: List<List<String>>,
        pubkey: String = relayKey,
        createdAt: Long = 100,
        id: String = "id-$kind-$createdAt",
        content: String = "",
    ) = NostrEvent(id, pubkey, createdAt, kind, tags, content, "sig")

    @Test
    fun metadataReadsNameAndFlags() {
        val meta = parseGroupMetadata(
            event(
                GROUP_METADATA_KIND,
                listOf(
                    listOf("d", "g1"),
                    listOf("name", "Nostr JP"),
                    listOf("picture", "https://example.com/p.png"),
                    listOf("about", "雑談"),
                    listOf("private"),
                    listOf("closed"),
                    listOf("supported_kinds", "9", "7"),
                ),
            ),
            relayPubkey = relayKey,
        )!!

        assertEquals("g1", meta.groupId)
        assertEquals("Nostr JP", meta.name)
        assertTrue(meta.isPrivate)
        assertTrue(meta.isClosed)
        assertFalse(meta.isRestricted)
        assertFalse(meta.isHidden)
        assertTrue(meta.supportsKind(9))
        assertFalse(meta.supportsKind(11))
    }

    @Test
    fun metadataWithoutSupportedKindsAllowsEverything() {
        val meta = parseGroupMetadata(event(GROUP_METADATA_KIND, listOf(listOf("d", "g1"))), relayKey)!!

        assertTrue(meta.supportsKind(9))
        assertEquals("g1", meta.displayName)
    }

    @Test
    fun metadataSignedByOthersOrWithoutIdIsRejected() {
        assertNull(parseGroupMetadata(event(GROUP_METADATA_KIND, listOf(listOf("d", "g1")), pubkey = other), relayKey))
        assertNull(parseGroupMetadata(event(GROUP_METADATA_KIND, emptyList()), relayKey))
        assertNull(parseGroupMetadata(event(1, listOf(listOf("d", "g1"))), relayKey))
    }

    @Test
    fun metadataIsAcceptedFromAnyKeyWhenRelayKeyIsUnknown() {
        assertEquals("g1", parseGroupMetadata(event(GROUP_METADATA_KIND, listOf(listOf("d", "g1")), pubkey = other), null)?.groupId)
    }

    @Test
    fun latestMetadataWinsPerGroup() {
        val older = event(GROUP_METADATA_KIND, listOf(listOf("d", "g1"), listOf("name", "old")), createdAt = 1)
        val newer = event(GROUP_METADATA_KIND, listOf(listOf("d", "g1"), listOf("name", "new")), createdAt = 2)

        val latest = latestGroupMetadata(listOf(newer, older), relayKey)

        assertEquals("new", latest.getValue("g1").name)
    }

    @Test
    fun adminsAreReadFromRelaySignedList() {
        val admins = parseGroupAdmins(
            event(GROUP_ADMINS_KIND, listOf(listOf("d", "g1"), listOf("p", admin, "admin"), listOf("p", "short"))),
            relayKey,
        )
        assertEquals(setOf(admin), admins)
    }

    @Test
    fun membershipFollowsTheLatestPutOrRemove() {
        val put = event(GROUP_PUT_USER_KIND, listOf(listOf("h", "g1"), listOf("p", me)), createdAt = 10)
        val remove = event(GROUP_REMOVE_USER_KIND, listOf(listOf("h", "g1"), listOf("p", me)), createdAt = 20)
        val putAgain = event(GROUP_PUT_USER_KIND, listOf(listOf("h", "g1"), listOf("p", me)), createdAt = 30)
        val authorities = setOf(relayKey)

        assertEquals(GroupMembership.NotMember, membershipOf(me, "g1", emptyList(), authorities))
        assertEquals(GroupMembership.Member, membershipOf(me, "g1", listOf(put), authorities))
        assertEquals(GroupMembership.NotMember, membershipOf(me, "g1", listOf(put, remove), authorities))
        assertEquals(GroupMembership.Member, membershipOf(me, "g1", listOf(remove, putAgain, put), authorities))
    }

    @Test
    fun membershipIgnoresOtherUsersGroupsAndUnauthorizedSigners() {
        val forOther = event(GROUP_PUT_USER_KIND, listOf(listOf("h", "g1"), listOf("p", other)))
        val otherGroup = event(GROUP_PUT_USER_KIND, listOf(listOf("h", "g2"), listOf("p", me)))
        val forged = event(GROUP_PUT_USER_KIND, listOf(listOf("h", "g1"), listOf("p", me)), pubkey = other)

        assertEquals(
            GroupMembership.NotMember,
            membershipOf(me, "g1", listOf(forOther, otherGroup, forged), setOf(relayKey, admin)),
        )
        val byAdmin = event(GROUP_PUT_USER_KIND, listOf(listOf("h", "g1"), listOf("p", me)), pubkey = admin)
        assertEquals(GroupMembership.Member, membershipOf(me, "g1", listOf(byAdmin), setOf(relayKey, admin)))
    }
}

class GroupLayoutTest {
    private val relayKey = "c".repeat(64)
    private val me = "a".repeat(64)
    private fun meta(vararg tags: List<String>) =
        parseGroupMetadata(NostrEvent("x", relayKey, 1, GROUP_METADATA_KIND, listOf(listOf("d", "g")) + tags, "", "s"), relayKey)!!

    @Test
    fun buzzChannelTypeDecidesLayout() {
        assertEquals(GroupLayout.Forum, meta(listOf("t", "forum")).layout)
        assertEquals(GroupLayout.Chat, meta(listOf("t", "stream"), listOf("t", "label")).layout)
        assertEquals(GroupLayout.Direct, meta(listOf("t", "dm")).layout)
        assertEquals(GroupLayout.Hidden, meta(listOf("t", "system")).layout)
    }

    @Test
    fun buzzArchivedTagMarksTheChannelArchived() {
        assertTrue(meta(listOf("t", "stream"), listOf("archived", "true")).isArchived)
        // リレーは稼働中のチャンネルにはタグを付けない。"false" や不正な値はアーカイブ扱いにしない。
        assertFalse(meta(listOf("t", "stream")).isArchived)
        assertFalse(meta(listOf("archived", "false")).isArchived)
        assertFalse(meta(listOf("archived")).isArchived)
    }

    @Test
    fun standardThreadOnlyGroupIsForum() {
        assertEquals(GroupLayout.Forum, meta(listOf("supported_kinds", "11", "1111", "7")).layout)
        assertEquals(GroupLayout.Chat, meta(listOf("supported_kinds", "9", "11")).layout)
        assertEquals(GroupLayout.Chat, meta().layout)
    }

    @Test
    fun buzzMembershipComesFromTheLatestMemberList() {
        val old = NostrEvent("1", relayKey, 1, GROUP_MEMBERS_KIND, listOf(listOf("d", "g"), listOf("p", me)), "", "s")
        val new = NostrEvent("2", relayKey, 2, GROUP_MEMBERS_KIND, listOf(listOf("d", "g")), "", "s")
        val forged = NostrEvent("3", me, 3, GROUP_MEMBERS_KIND, listOf(listOf("d", "g"), listOf("p", me)), "", "s")
        assertEquals(GroupMembership.Member, buzzMembershipOf(me, "g", listOf(old), relayKey))
        assertEquals(GroupMembership.NotMember, buzzMembershipOf(me, "g", listOf(old, new, forged), relayKey))
    }
}
