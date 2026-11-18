package com.nostr.torinos.chat

import com.nostr.torinos.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// docs/buzz-admin-design.md 4.1
class ChannelAdministrationTest {
    private val relayKey = "f".repeat(64)
    private val me = "a".repeat(64)
    private val alice = "b".repeat(64)
    private val bob = "c".repeat(64)
    private val carol = "d".repeat(64)
    private val id = "10000000-0000-4000-8000-000000000001"

    private fun metadata(vararg tags: List<String>) =
        parseGroupMetadata(NostrEvent("1".repeat(64), relayKey, 100, GROUP_METADATA_KIND, listOf(listOf("d", id)) + tags, "", "sig"), relayKey)!!

    private val stream = metadata(listOf("name", "peko"), listOf("t", "stream"))
    private val archived = metadata(listOf("name", "peko"), listOf("t", "stream"), listOf("archived", "true"))
    private val dm = metadata(listOf("t", "dm"), listOf("p", me), listOf("p", alice))
    private val solo = metadata(listOf("t", "stream"), listOf("t", BUZZ_SOLO_LABEL))

    private fun members(
        vararg entries: Pair<String, String>,
        eventId: String = "2".repeat(64),
        signer: String = relayKey,
        createdAt: Long = 100,
        groupId: String = id,
    ) = NostrEvent(
        eventId, signer, createdAt, GROUP_MEMBERS_KIND,
        listOf(listOf("d", groupId), listOf("t", "stream")) + entries.map { (key, role) -> listOf("p", key, "", role) },
        "", "sig",
    )

    // ---- 役割の読み取り ----

    @Test
    fun rolesAreReadFromTheLatestRelaySignedMemberList() {
        val old = members(me to "member", createdAt = 100, eventId = "3".repeat(64))
        val latest = members(me to "admin", alice to "owner", bob to "guest", carol to "bot", createdAt = 200)
        val forged = members(me to "owner", signer = alice, createdAt = 300, eventId = "4".repeat(64))
        val other = members(me to "owner", groupId = "other", createdAt = 400, eventId = "5".repeat(64))
        val roles = channelRoles(listOf(old, latest, forged, other), id, relayKey)!!
        assertEquals(
            mapOf(me to ChannelRole.Admin, alice to ChannelRole.Owner, bob to ChannelRole.Guest, carol to ChannelRole.Bot),
            roles,
        )
    }

    @Test
    fun rolesNeedTheRelayKey() {
        // 署名者を確かめられないときは役割を読まない（管理の操作を出さない）。
        assertNull(channelRoles(listOf(members(me to "owner")), id, relayPubkey = null))
        assertNull(channelRoles(emptyList(), id, relayKey))
    }

    @Test
    fun unknownAndMissingRolesAreReadAsSuch() {
        val event = NostrEvent(
            "2".repeat(64), relayKey, 100, GROUP_MEMBERS_KIND,
            listOf(listOf("d", id), listOf("p", me), listOf("p", alice, "", "wizard"), listOf("p", "not-a-key", "", "owner")),
            "", "sig",
        )
        assertEquals(mapOf(me to ChannelRole.Member, alice to ChannelRole.Unknown), channelRoles(listOf(event), id, relayKey))
    }

    @Test
    fun latestMemberListIdIsTracked() {
        val a = members(me to "member", createdAt = 100, eventId = "3".repeat(64))
        val b = members(me to "admin", createdAt = 200, eventId = "4".repeat(64))
        assertEquals("4".repeat(64), latestMemberListId(listOf(a, b), id, relayKey))
        assertNull(latestMemberListId(listOf(a), id, relayPubkey = null))
    }

    // ---- 権限 ----

    @Test
    fun ownersAndAdminsManageActiveChannels() {
        val roles = mapOf(me to ChannelRole.Admin, alice to ChannelRole.Owner)
        val authority = channelAuthority(stream, roles, me)
        assertEquals(ChannelRole.Admin, authority.viewerRole)
        assertTrue(authority.canManage)
        assertTrue(authority.canRemove)
        assertTrue(authority.canArchive)
        assertFalse(authority.canUnarchive)
        // 削除は owner だけ。
        assertFalse(authority.canDelete)
        assertFalse(authority.isLastOwner)

        val owner = channelAuthority(stream, mapOf(me to ChannelRole.Owner), me)
        assertTrue(owner.canDelete)
        assertTrue(owner.isLastOwner)
        assertFalse(channelAuthority(stream, mapOf(me to ChannelRole.Owner, alice to ChannelRole.Owner), me).isLastOwner)
    }

    @Test
    fun membersGuestsAndOutsidersManageNothing() {
        listOf(ChannelRole.Member, ChannelRole.Guest, ChannelRole.Bot, ChannelRole.Unknown).forEach { role ->
            val authority = channelAuthority(stream, mapOf(me to role), me)
            assertFalse(authority.canManage, "$role")
            assertFalse(authority.canRemove, "$role")
            assertFalse(authority.canArchive, "$role")
            assertFalse(authority.canDelete, "$role")
        }
        val outsider = channelAuthority(stream, mapOf(alice to ChannelRole.Owner), me)
        assertNull(outsider.viewerRole)
        assertFalse(outsider.canManage)
        assertFalse(channelAuthority(stream, null, me).canManage)
        assertFalse(channelAuthority(stream, mapOf(me to ChannelRole.Owner), null).canManage)
        assertFalse(channelAuthority(null, mapOf(me to ChannelRole.Owner), me).canManage)
    }

    @Test
    fun archivedChannelsOnlyOfferUnarchive() {
        val authority = channelAuthority(archived, mapOf(me to ChannelRole.Owner), me)
        assertFalse(authority.canManage)
        assertFalse(authority.canRemove)
        assertFalse(authority.canArchive)
        assertTrue(authority.canUnarchive)
        assertFalse(authority.canDelete)
    }

    @Test
    fun dmsHaveNoAdministration() {
        val authority = channelAuthority(dm, mapOf(me to ChannelRole.Owner), me)
        assertFalse(authority.canManage)
        assertFalse(authority.canRemove)
        assertFalse(authority.canArchive)
        assertFalse(authority.canUnarchive)
        assertFalse(authority.canDelete)
        // DM の退出は扱わないので、最後の owner の案内も出さない。
        assertFalse(authority.isLastOwner)
    }

    @Test
    fun soloChatsAllowRemovalButNotRoleChanges() {
        val authority = channelAuthority(solo, mapOf(me to ChannelRole.Owner), me)
        assertFalse(authority.canManage)
        assertTrue(authority.canRemove)
    }

    @Test
    fun targetsExcludeSelfOwnersAndUnknownRoles() {
        val roles = mapOf(
            me to ChannelRole.Admin, alice to ChannelRole.Owner, bob to ChannelRole.Member,
            carol to ChannelRole.Unknown, "e".repeat(64) to ChannelRole.Bot,
        )
        val authority = channelAuthority(stream, roles, me)
        assertTrue(canChangeRole(authority, roles, me, bob))
        assertTrue(canChangeRole(authority, roles, me, "e".repeat(64)))
        assertFalse(canChangeRole(authority, roles, me, me))
        assertFalse(canChangeRole(authority, roles, me, alice))
        assertFalse(canChangeRole(authority, roles, me, carol))
        assertFalse(canChangeRole(authority, roles, me, "9".repeat(64)))
        assertTrue(canRemoveMember(authority, roles, me, bob))
        assertFalse(canRemoveMember(authority, roles, me, alice))
        assertFalse(canRemoveMember(authority, roles, me, me))
    }

    @Test
    fun roleChoicesExcludeTheCurrentRole() {
        assertEquals(listOf(ChannelRole.Member, ChannelRole.Guest), roleChoices(ChannelRole.Admin))
        assertEquals(listOf(ChannelRole.Admin, ChannelRole.Guest), roleChoices(ChannelRole.Member))
        assertEquals(listOf(ChannelRole.Admin, ChannelRole.Member), roleChoices(ChannelRole.Guest))
        // bot は member と同じ扱い（リレーは member の指定を bot として保存する）。
        assertEquals(listOf(ChannelRole.Admin, ChannelRole.Guest), roleChoices(ChannelRole.Bot))
    }

    // ---- メンバーによる追加（Buzz の decide_member_put） ----

    @Test
    fun membersAndBotsCanAddAsMemberOrGuest() {
        listOf(ChannelRole.Member, ChannelRole.Bot).forEach { role ->
            val authority = channelAuthority(stream, mapOf(me to role), me)
            assertTrue(authority.canAdd, "$role")
            assertEquals(listOf(ChannelRole.Member, ChannelRole.Guest), authority.addableRoles, "$role")
            assertFalse(authority.canManage, "$role")
        }
        val admin = channelAuthority(stream, mapOf(me to ChannelRole.Admin), me)
        assertTrue(admin.canAdd)
        assertEquals(listOf(ChannelRole.Member, ChannelRole.Guest, ChannelRole.Admin), admin.addableRoles)
    }

    @Test
    fun guestsOutsidersArchivedDmAndSoloCannotAdd() {
        assertFalse(channelAuthority(stream, mapOf(me to ChannelRole.Guest), me).canAdd)
        assertFalse(channelAuthority(stream, mapOf(alice to ChannelRole.Owner), me).canAdd)
        assertFalse(channelAuthority(archived, mapOf(me to ChannelRole.Owner), me).canAdd)
        assertFalse(channelAuthority(dm, mapOf(me to ChannelRole.Owner), me).canAdd)
        assertFalse(channelAuthority(solo, mapOf(me to ChannelRole.Member), me).canAdd)
        assertEquals(emptyList(), channelAuthority(stream, mapOf(me to ChannelRole.Guest), me).addableRoles)
    }

    @Test
    fun membersCanOnlyPromoteGuestsToMember() {
        val roles = mapOf(me to ChannelRole.Member, bob to ChannelRole.Guest, carol to ChannelRole.Member, alice to ChannelRole.Admin)
        val authority = channelAuthority(stream, roles, me)
        assertTrue(canChangeRole(authority, roles, me, bob))
        assertEquals(listOf(ChannelRole.Member), roleChoicesFor(authority, ChannelRole.Guest))
        assertFalse(canChangeRole(authority, roles, me, carol))
        assertFalse(canChangeRole(authority, roles, me, alice))
        assertEquals(emptyList(), roleChoicesFor(authority, ChannelRole.Member))
        // 外すのは管理者だけ。
        assertFalse(canRemoveMember(authority, roles, me, bob))
        // 管理者は今までどおり。
        val adminRoles = roles + (me to ChannelRole.Admin)
        val adminAuthority = channelAuthority(stream, adminRoles, me)
        assertEquals(roleChoices(ChannelRole.Guest), roleChoicesFor(adminAuthority, ChannelRole.Guest))
    }

    // ---- 命令の形 ----

    @Test
    fun putUserCarriesTheRole() {
        val draft = buildChannelPutUser(id, bob, ChannelRole.Guest)
        assertEquals(GROUP_PUT_USER_KIND, draft.kind)
        assertEquals(listOf(listOf("h", id), listOf("p", bob), listOf("role", "guest")), draft.tags)
        assertEquals("", draft.content)
        assertEquals(listOf("role", "admin"), buildChannelPutUser(id, bob.uppercase(), ChannelRole.Admin).tags.last())
        assertEquals(listOf("p", bob), buildChannelPutUser(id, bob.uppercase(), ChannelRole.Member).tags[1])
    }

    @Test
    fun putUserRefusesOwnerAndInvalidKeys() {
        assertFailsWith<IllegalArgumentException> { buildChannelPutUser(id, bob, ChannelRole.Owner) }
        assertFailsWith<IllegalArgumentException> { buildChannelPutUser(id, bob, ChannelRole.Bot) }
        assertFailsWith<IllegalArgumentException> { buildChannelPutUser(id, bob, ChannelRole.Unknown) }
        assertFailsWith<IllegalArgumentException> { buildChannelPutUser(id, "npub1xyz", ChannelRole.Member) }
    }

    @Test
    fun removeArchiveDeleteAndAdminDeletion() {
        assertEquals(GroupEventDraft(GROUP_REMOVE_USER_KIND, listOf(listOf("h", id), listOf("p", bob)), ""), buildChannelRemoveUser(id, bob))
        assertEquals(GroupEventDraft(GROUP_EDIT_METADATA_KIND, listOf(listOf("h", id), listOf("archived", "true")), ""), buildChannelArchive(id, true))
        assertEquals(GroupEventDraft(GROUP_EDIT_METADATA_KIND, listOf(listOf("h", id), listOf("archived", "false")), ""), buildChannelArchive(id, false))
        assertEquals(GroupEventDraft(GROUP_DELETE_GROUP_KIND, listOf(listOf("h", id)), ""), buildChannelDelete(id))
        val target = "7".repeat(64)
        // Buzz は対象がちょうど1つでないと断る。
        assertEquals(GroupEventDraft(GROUP_DELETE_EVENT_KIND, listOf(listOf("h", id), listOf("e", target)), ""), buildAdminDeletion(id, target))
        assertFailsWith<IllegalArgumentException> { buildChannelRemoveUser(id, "x") }
        assertFailsWith<IllegalArgumentException> { buildAdminDeletion(id, "x") }
    }

    @Test
    fun adminCommandsPassTheGroupPublishCheck() {
        listOf(
            buildChannelPutUser(id, bob, ChannelRole.Member),
            buildChannelRemoveUser(id, bob),
            buildChannelArchive(id, true),
            buildChannelDelete(id),
            buildAdminDeletion(id, "7".repeat(64)),
        ).forEach { draft ->
            val event = NostrEvent("2".repeat(64), me, 100, draft.kind, draft.tags, draft.content, "sig")
            assertNull(groupPublishViolation(event, id, setOf("wss://x"), "wss://x"), "${draft.kind}")
            assertEquals(GroupPublishViolation.WrongRelay, groupPublishViolation(event, id, setOf("wss://y"), "wss://x"))
        }
    }

    // ---- 反映待ち ----

    @Test
    fun pendingChangesOverlayTheRoles() {
        val roles = mapOf(me to ChannelRole.Owner, bob to ChannelRole.Member, carol to ChannelRole.Guest)
        val pending = mapOf(bob to ChannelRole.Admin, carol to null, alice to ChannelRole.Member)
        assertEquals(
            mapOf(me to ChannelRole.Owner, bob to ChannelRole.Admin, alice to ChannelRole.Member),
            applyPendingRoles(roles, pending),
        )
        assertEquals(roles, applyPendingRoles(roles, emptyMap()))
    }

    // ---- 拒否理由 ----

    @Test
    fun knownRejectionsAreTranslated() {
        assertEquals("権限がありません", adminRejectionMessage("invalid: actor not authorized: must be admin or owner"))
        assertEquals("管理者にできるのはオーナーだけです", adminRejectionMessage("invalid: actor not authorized: only owner can grant admin role"))
        assertEquals("管理者が外せるのはメンバーだけです", adminRejectionMessage("invalid: actor not authorized: admins can only remove members"))
        assertEquals("自分には使えません", adminRejectionMessage("invalid: cannot remove yourself"))
        assertEquals("自分には使えません", adminRejectionMessage("invalid: cannot change your own role"))
        assertEquals("オーナーには使えません", adminRejectionMessage("invalid: cannot remove the relay owner"))
        assertEquals("オーナーには使えません", adminRejectionMessage("invalid: cannot change the relay owner's role"))
        assertEquals("すでにメンバーではありません", adminRejectionMessage("invalid: member not found: " + bob))
        assertEquals("一時的に変更できません。しばらくしてからお試しください", adminRejectionMessage("community writes are temporarily unavailable"))
        assertEquals("ログインできていないか、権限がありません", adminRejectionMessage("restricted: only members can post in this channel"))
        assertEquals("ログインできていないか、権限がありません", adminRejectionMessage("auth-required: not authenticated"))
        assertEquals("受け付けられませんでした（error: boom）", adminRejectionMessage("error: boom"))
        assertEquals("受け付けられませんでした", adminRejectionMessage(""))
    }
}
