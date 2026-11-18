package com.nostr.torinos.chat

import com.nostr.torinos.model.NostrEvent
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupCreationTest {
    private val relay = "wss://png.example.com"
    private val me = "a".repeat(64)
    private val other = "b".repeat(64)
    private val third = "c".repeat(64)
    private val uuid = "10000000-0000-4000-8000-000000000001"

    private fun spec(
        name: String = "release-notes",
        about: String = "",
        type: NewGroupType = NewGroupType.Chat,
        visibility: NewGroupVisibility = NewGroupVisibility.Open,
    ) = NewGroupSpec(groupId = uuid, name = name, about = about, type = type, visibility = visibility)

    private fun signed(draft: GroupEventDraft) = NostrEvent("1".repeat(64), me, 100, draft.kind, draft.tags, draft.content, "sig")

    // ---- 名前 ----

    @Test
    fun nameDropsLeadingHashAndSurroundingSpaces() {
        assertEquals("general", canonicalGroupName("  # #general  "))
        assertEquals("雑談 部屋", canonicalGroupName("#雑談 部屋\n"))
    }

    @Test
    fun nameMustBeOneTo255Characters() {
        assertEquals(GroupNameError.Empty, groupNameError(" # "))
        assertNull(groupNameError("a".repeat(255)))
        assertEquals(GroupNameError.TooLong, groupNameError("a".repeat(256)))
        // 先頭の # は数えない。
        assertNull(groupNameError("#" + "a".repeat(255)))
    }

    // ---- ID ----

    @Test
    fun buzzChannelIdIsUuidV4() {
        val pattern = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        repeat(50) { seed -> assertTrue(pattern.matches(newBuzzChannelId(Random(seed)))) }
        assertNotEquals(newBuzzChannelId(Random(1)), newBuzzChannelId(Random(2)))
    }

    @Test
    fun standardGroupIdIsLowercaseAlphanumeric() {
        repeat(50) { seed -> assertTrue(Regex("^[a-z0-9]{12}$").matches(newStandardGroupId(Random(seed)))) }
    }

    // ---- Buzz のチャンネル作成 ----

    @Test
    fun buzzCreateUsesTheTagOrderOfTheBuzzClients() {
        val draft = buildBuzzCreateChannel(spec(name = " #release-notes ", about = " リリースの告知 "))

        assertEquals(GROUP_CREATE_KIND, draft.kind)
        assertEquals("", draft.content)
        assertEquals(
            listOf(
                listOf("h", uuid),
                listOf("name", "release-notes"),
                listOf("visibility", "open"),
                listOf("channel_type", "stream"),
                listOf("about", "リリースの告知"),
            ),
            draft.tags,
        )
    }

    @Test
    fun buzzCreateOmitsBlankAboutAndMapsTypeAndVisibility() {
        val draft = buildBuzzCreateChannel(spec(about = "  ", type = NewGroupType.Forum, visibility = NewGroupVisibility.Private))

        assertEquals(listOf("visibility", "private"), draft.tags.single { it[0] == "visibility" })
        assertEquals(listOf("channel_type", "forum"), draft.tags.single { it[0] == "channel_type" })
        assertTrue(draft.tags.none { it[0] == "about" })
        assertTrue(draft.tags.none { it[0] == "ttl" || it[0] == "unlisted" })
    }

    @Test
    fun buzzCreateCarriesLabelsAndInitialMembers() {
        val draft = buildBuzzCreateChannel(spec().copy(labels = listOf("buzz.solo"), initialMembers = listOf(other, other)))

        assertEquals(listOf(listOf("t", "buzz.solo")), draft.tags.filter { it[0] == "t" })
        assertEquals(listOf(listOf("p", other)), draft.tags.filter { it[0] == "p" })
    }

    @Test
    fun buzzCreateRefusesAnInvalidName() {
        assertFailsWith<IllegalArgumentException> { buildBuzzCreateChannel(spec(name = "#")) }
    }

    // ---- 標準の NIP-29 のグループ作成 ----

    @Test
    fun standardCreateCarriesOnlyTheGroupId() {
        val draft = buildStandardCreateGroup("abc123")

        assertEquals(GROUP_CREATE_KIND, draft.kind)
        assertEquals(listOf(listOf("h", "abc123")), draft.tags)
    }

    @Test
    fun standardEditMetadataSetsNameAboutAndPrivateFlags() {
        val open = buildStandardEditMetadata(spec(about = "説明").copy(groupId = "abc123"))
        assertEquals(GROUP_EDIT_METADATA_KIND, open.kind)
        assertEquals(listOf(listOf("h", "abc123"), listOf("name", "release-notes"), listOf("about", "説明")), open.tags)

        val private = buildStandardEditMetadata(spec(visibility = NewGroupVisibility.Private).copy(groupId = "abc123"))
        assertEquals(
            listOf(listOf("h", "abc123"), listOf("name", "release-notes"), listOf("private"), listOf("closed")),
            private.tags,
        )
    }

    // ---- 拒否理由 ----

    @Test
    fun rejectionReasonsAreClassified() {
        // Buzz の `ChannelNameTaken`。
        assertEquals(CreateRejection.NameTaken, classifyCreateRejection("invalid: an open channel named \"general\" already exists"))
        assertEquals(CreateRejection.InvalidName, classifyCreateRejection("invalid: channel name is required"))
        assertEquals(CreateRejection.InvalidName, classifyCreateRejection("invalid: channel name is longer than 255 characters"))
        assertEquals(CreateRejection.IdTaken, classifyCreateRejection("duplicate: channel already exists"))
        assertEquals(CreateRejection.NotAllowed, classifyCreateRejection("restricted: not allowed to create channels"))
        assertEquals(CreateRejection.NotAllowed, classifyCreateRejection("blocked: only admins"))
        assertEquals(CreateRejection.Other, classifyCreateRejection("error: database error"))
    }

    // ---- 送信の検査 ----

    @Test
    fun createAndEditMetadataPassThePublishCheck() {
        val create = signed(buildBuzzCreateChannel(spec()))
        assertNull(groupPublishViolation(create, uuid, setOf(relay), relay))
        assertEquals(GroupPublishViolation.WrongRelay, groupPublishViolation(create, uuid, setOf(relay, "wss://other.example.com"), relay))
        assertEquals(GroupPublishViolation.MissingGroupTag, groupPublishViolation(create, "another-id", setOf(relay), relay))

        val edit = signed(buildStandardEditMetadata(spec().copy(groupId = "abc123")))
        assertNull(groupPublishViolation(edit, "abc123", setOf(relay), relay))
    }

    // ---- Buzz の DM ----

    @Test
    fun openDmListsTheOthersOnce() {
        val draft = buildBuzzOpenDm(listOf(other, me, other.uppercase(), third), me)

        assertEquals(BUZZ_DM_OPEN_KIND, draft.kind)
        assertEquals("", draft.content)
        assertEquals(listOf(listOf("p", other), listOf("p", third)), draft.tags)
    }

    @Test
    fun openDmNeedsOneToEightOthers() {
        assertFailsWith<IllegalArgumentException> { buildBuzzOpenDm(listOf(me), me) }
        assertFailsWith<IllegalArgumentException> { buildBuzzOpenDm(listOf("not-a-key"), me) }
        val nine = (1..9).map { it.toString().repeat(64) }
        assertFailsWith<IllegalArgumentException> { buildBuzzOpenDm(nine, me) }
        assertEquals(8, buildBuzzOpenDm(nine.take(8), me).tags.size)
    }

    @Test
    fun commandResponseIsReadFromTheOkMessage() {
        assertEquals(uuid, buzzDmChannelId("""response:{"channel_id":"$uuid","created":true}"""))
        // 古いリレーは response: を付けない。
        assertEquals(uuid, buzzDmChannelId("""{"channel_id":"$uuid","created":false}"""))
        assertNull(buzzDmChannelId("duplicate: already processed"))
        assertNull(buzzDmChannelId("response:{broken"))
        assertNull(buzzDmChannelId("""response:{"channel_id":""}"""))
        assertNull(buzzDmChannelId("""response:{"channel_id":42}"""))
        assertNull(buzzDmChannelId(""))
    }

    @Test
    fun commandsGoOnlyToTheirBuzzCommunity() {
        val open = signed(buildBuzzOpenDm(listOf(other), me).let { GroupEventDraft(it.kind, it.tags, it.content) })

        assertNull(buzzCommandViolation(open, setOf(relay), relay, GroupFlavor.Buzz))
        assertEquals(BuzzCommandViolation.NotBuzz, buzzCommandViolation(open, setOf(relay), relay, GroupFlavor.Standard))
        assertEquals(BuzzCommandViolation.WrongRelay, buzzCommandViolation(open, setOf(relay, "wss://relay.example.com"), relay, GroupFlavor.Buzz))
        assertEquals(BuzzCommandViolation.WrongRelay, buzzCommandViolation(open, setOf("wss://relay.example.com"), relay, GroupFlavor.Buzz))
        val message = signed(GroupEventDraft(GROUP_CHAT_KIND, listOf(listOf("h", uuid)), "hi"))
        assertEquals(BuzzCommandViolation.UnsupportedKind, buzzCommandViolation(message, setOf(relay), relay, GroupFlavor.Buzz))
        assertFalse(GROUP_PUBLISHABLE_KINDS.contains(BUZZ_DM_OPEN_KIND))
    }
}
