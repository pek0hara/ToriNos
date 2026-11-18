package com.nostr.torinos.chat

import com.nostr.torinos.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupEditingTest {
    private val relayKey = "f".repeat(64)
    private val me = "a".repeat(64)
    private val other = "b".repeat(64)
    private val id = "10000000-0000-4000-8000-000000000001"

    private fun metadata(vararg tags: List<String>) =
        parseGroupMetadata(NostrEvent("1".repeat(64), relayKey, 100, GROUP_METADATA_KIND, listOf(listOf("d", id)) + tags, "", "sig"), relayKey)!!

    private val open = metadata(listOf("name", "peko"), listOf("about", "old"), listOf("public"), listOf("t", "stream"), listOf("P", me))
    private val private = metadata(listOf("name", "peko"), listOf("private"), listOf("restricted"), listOf("closed"), listOf("t", "stream"), listOf("P", other))

    private fun signed(draft: GroupEventDraft) = NostrEvent("2".repeat(64), me, 100, draft.kind, draft.tags, draft.content, "sig")

    // ---- 誰が編集できるか ----

    @Test
    fun creatorIsReadFromTheMetadata() {
        assertEquals(me, open.creator)
        assertNull(metadata(listOf("name", "x")).creator)
        assertNull(metadata(listOf("P", "not-a-key")).creator)
    }

    @Test
    fun creatorAndAdminsCanEditButNotOthersOrDms() {
        assertTrue(canEditGroup(open, me, admins = emptySet()))
        assertTrue(canEditGroup(private, me, admins = setOf(me)))
        assertFalse(canEditGroup(private, me, admins = setOf(other)))
        assertFalse(canEditGroup(null, me, admins = setOf(me)))
        assertFalse(canEditGroup(open, null, admins = emptySet()))
        val dm = metadata(listOf("t", "dm"), listOf("P", me), listOf("p", me), listOf("p", other))
        assertFalse(canEditGroup(dm, me, admins = setOf(me)))
    }

    // ---- 変更の有無 ----

    @Test
    fun editFormStartsFromTheCurrentValues() {
        assertEquals(GroupEdit("peko", "old", NewGroupVisibility.Open), groupEditOf(open))
        assertEquals(GroupEdit("peko", "", NewGroupVisibility.Private), groupEditOf(private))
    }

    @Test
    fun unchangedOrInvalidEditCannotBeSaved() {
        assertFalse(isGroupEditSavable(open, groupEditOf(open)))
        // 名前の前後の空白と # は変更に数えない。
        assertFalse(isGroupEditSavable(open, groupEditOf(open).copy(name = " #peko ")))
        assertFalse(isGroupEditSavable(open, groupEditOf(open).copy(name = " # ")))
        assertFalse(isGroupEditSavable(open, groupEditOf(open).copy(name = "a".repeat(256))))
        assertTrue(isGroupEditSavable(open, groupEditOf(open).copy(name = "peko2")))
        assertTrue(isGroupEditSavable(open, groupEditOf(open).copy(about = "")))
        assertTrue(isGroupEditSavable(open, groupEditOf(open).copy(visibility = NewGroupVisibility.Private)))
    }

    // ---- Buzz: 変えた項目だけを送る ----

    @Test
    fun buzzEditSendsOnlyTheChangedFields() {
        val rename = buildBuzzEditMetadata(open, groupEditOf(open).copy(name = " #peko2 "))!!
        assertEquals(GROUP_EDIT_METADATA_KIND, rename.kind)
        assertEquals(listOf(listOf("h", id), listOf("name", "peko2")), rename.tags)

        val all = buildBuzzEditMetadata(open, GroupEdit("peko2", " new ", NewGroupVisibility.Private))!!
        assertEquals(
            listOf(listOf("h", id), listOf("name", "peko2"), listOf("about", "new"), listOf("visibility", "private")),
            all.tags,
        )
    }

    @Test
    fun buzzEditCanClearTheDescriptionAndNeverTouchesLabels() {
        val cleared = buildBuzzEditMetadata(open, groupEditOf(open).copy(about = "  "))!!
        assertEquals(listOf(listOf("h", id), listOf("about", "")), cleared.tags)
        // `t` を送るとラベルの集合が置き換わる。
        assertTrue(cleared.tags.none { it[0] == "t" })

        assertEquals(listOf("visibility", "open"), buildBuzzEditMetadata(private, groupEditOf(private).copy(visibility = NewGroupVisibility.Open))!!.tags.last())
    }

    @Test
    fun buzzEditWithoutChangesIsNotBuilt() {
        assertNull(buildBuzzEditMetadata(open, groupEditOf(open)))
        assertNull(buildBuzzEditMetadata(open, groupEditOf(open).copy(name = "#")))
    }

    // ---- Buzz: 参加時の役割と一覧に出さない設定（docs/buzz-admin-design.md 3.3） ----

    @Test
    fun joinRoleAndUnlistedAreReadFromTheMetadata() {
        val announce = metadata(listOf("name", "news"), listOf("public"), listOf("restricted"), listOf("join_role", "guest"), listOf("unlisted"), listOf("t", "stream"))
        assertEquals("guest", announce.joinRole)
        assertTrue(announce.isUnlisted)
        assertNull(open.joinRole)
        assertFalse(open.isUnlisted)
        assertEquals(GroupJoinRole.Guest, groupEditOf(announce).joinRole)
        assertTrue(groupEditOf(announce).unlisted)
        // 知らない値は Buzz と同じく guest とみなす。
        assertEquals(GroupJoinRole.Guest, groupEditOf(metadata(listOf("join_role", "wizard"))).joinRole)
        assertEquals(GroupJoinRole.Member, groupEditOf(open).joinRole)
    }

    @Test
    fun buzzEditSendsJoinRoleAndUnlistedOnlyWhenChanged() {
        val guest = buildBuzzEditMetadata(open, groupEditOf(open).copy(joinRole = GroupJoinRole.Guest))!!
        assertEquals(listOf(listOf("h", id), listOf("join_role", "guest")), guest.tags)
        val unlisted = buildBuzzEditMetadata(open, groupEditOf(open).copy(unlisted = true))!!
        assertEquals(listOf(listOf("h", id), listOf("unlisted", "true")), unlisted.tags)
        val announce = metadata(listOf("name", "news"), listOf("join_role", "guest"), listOf("unlisted"), listOf("t", "stream"))
        val back = buildBuzzEditMetadata(announce, groupEditOf(announce).copy(joinRole = GroupJoinRole.Member, unlisted = false))!!
        assertEquals(listOf(listOf("h", id), listOf("join_role", "member"), listOf("unlisted", "false")), back.tags)
        assertTrue(isGroupEditSavable(open, groupEditOf(open).copy(unlisted = true)))
    }

    @Test
    fun editedMetadataShowsJoinRoleAndUnlistedUntilTheRelayAnswers() {
        val edited = open.withEdit(groupEditOf(open).copy(joinRole = GroupJoinRole.Guest, unlisted = true), GroupFlavor.Buzz)
        assertEquals("guest", edited.joinRole)
        assertTrue(edited.isUnlisted)
        // 公開のまま guest にすると「メンバーだけが書ける」になる（Buzz の 39000 の restricted）。
        assertTrue(edited.isRestricted)
        assertFalse(edited.isPrivate)
    }

    @Test
    fun standardEditIgnoresBuzzOnlySettings() {
        val current = parseGroupMetadata(
            NostrEvent("1".repeat(64), relayKey, 100, GROUP_METADATA_KIND, listOf(listOf("d", "abc"), listOf("name", "old")), "", "sig"),
            relayKey,
        )!!
        assertNull(buildStandardEditMetadata(current, groupEditOf(current).copy(joinRole = GroupJoinRole.Guest, unlisted = true)))
        assertFalse(isGroupEditSavable(current, groupEditOf(current).copy(unlisted = true), GroupFlavor.Standard))
    }

    // ---- 標準の NIP-29: 今の内容をすべて送る ----

    @Test
    fun standardEditResendsEveryCurrentFieldAndFlag() {
        val current = parseGroupMetadata(
            NostrEvent("1".repeat(64), relayKey, 100, GROUP_METADATA_KIND, listOf(listOf("d", "abc"), listOf("name", "old"), listOf("about", "desc"), listOf("picture", "https://example.com/p.png"), listOf("private"), listOf("closed")), "", "sig"),
            relayKey,
        )!!

        val draft = buildStandardEditMetadata(current, GroupEdit("new", "", NewGroupVisibility.Private))!!

        assertEquals(GROUP_EDIT_METADATA_KIND, draft.kind)
        assertEquals(
            listOf(listOf("h", "abc"), listOf("name", "new"), listOf("picture", "https://example.com/p.png"), listOf("private"), listOf("closed")),
            draft.tags,
        )
        assertNull(buildStandardEditMetadata(current, groupEditOf(current)))
    }

    @Test
    fun editsPassThePublishCheck() {
        val draft = buildBuzzEditMetadata(open, groupEditOf(open).copy(name = "peko2"))!!
        assertNull(groupPublishViolation(signed(draft), id, setOf("wss://buzz.example.com"), "wss://buzz.example.com"))
    }

    // ---- 拒否理由 ----

    @Test
    fun unauthorizedEditIsNotMistakenForAnInvalidName() {
        assertEquals(
            CreateRejection.NotAllowed,
            classifyCreateRejection("invalid: actor not authorized for name/about/archived/visibility/ttl/label/join_role/unlisted changes"),
        )
        assertEquals(CreateRejection.NotAllowed, classifyCreateRejection("restricted: not an admin"))
        assertEquals(CreateRejection.NameTaken, classifyCreateRejection("invalid: an open channel named \"peko2\" already exists"))
    }
}
