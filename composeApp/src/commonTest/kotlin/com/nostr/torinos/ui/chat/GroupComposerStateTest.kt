package com.nostr.torinos.ui.chat

import com.nostr.torinos.chat.GroupMembership
import com.nostr.torinos.chat.GroupMetadata
import com.nostr.torinos.chat.GroupRef
import kotlin.test.Test
import kotlin.test.assertEquals

class GroupComposerStateTest {
    private val ref = GroupRef("wss://g.example", "g1")

    private fun meta(restricted: Boolean = false, closed: Boolean = false) = GroupMetadata(
        groupId = "g1", name = "", picture = "", about = "",
        isPrivate = false, isRestricted = restricted, isHidden = false, isClosed = closed,
        supportedKinds = null, createdAt = 0,
    )

    @Test
    fun restrictedGroupCanStillBeJoined() {
        // restricted は「メンバーだけが書ける」。非メンバーは参加要求を送れる。
        val state = GroupChatUiState(ref = ref, metadata = meta(restricted = true), membershipChecked = true)
        assertEquals(GroupComposerState.JoinToWrite, state.composer)
    }

    @Test
    fun closedGroupIsReadOnlyWithoutInvite() {
        val state = GroupChatUiState(ref = ref, metadata = meta(closed = true), membershipChecked = true)
        assertEquals(GroupComposerState.ReadOnly, state.composer)
    }

    @Test
    fun memberCanWriteAndPendingWaits() {
        assertEquals(
            GroupComposerState.Writable,
            GroupChatUiState(ref = ref, metadata = meta(closed = true), membership = GroupMembership.Member).composer,
        )
        assertEquals(GroupComposerState.Pending, GroupChatUiState(ref = ref, isPending = true).composer)
        assertEquals(GroupComposerState.NoSigner, GroupChatUiState(ref = ref, hasSigner = false).composer)
    }

    @Test
    fun unknownMembershipBeforeRelayAnswersDoesNotOfferJoin() {
        // 入室直後はリレーからメンバー情報が届いていない。「参加する」を出さずに確認中にする。
        assertEquals(GroupComposerState.Checking, GroupChatUiState(ref = ref).composer)
    }

    @Test
    fun locallyJoinedGroupIsWritableUntilRelaySaysOtherwise() {
        // 参加済みの一覧にあるグループは、リレーの答えを待たずに書ける。
        assertEquals(GroupComposerState.Writable, GroupChatUiState(ref = ref, locallyJoined = true).composer)
        // リレーがメンバー情報を返さなくても、参加済みとして扱う。
        val noEvidence = GroupChatUiState(ref = ref, locallyJoined = true, membershipChecked = true)
        assertEquals(GroupComposerState.Writable, noEvidence.composer)
        assertEquals(true, noEvidence.isMember)
        // リレーがはっきり非メンバーと示したら、そちらに従う。
        val removed = GroupChatUiState(ref = ref, locallyJoined = true, membershipChecked = true, membership = GroupMembership.NotMember)
        assertEquals(GroupComposerState.JoinToWrite, removed.composer)
        assertEquals(false, removed.isMember)
    }

    @Test
    fun notJoinedAfterCheckOffersJoin() {
        assertEquals(GroupComposerState.JoinToWrite, GroupChatUiState(ref = ref, membershipChecked = true).composer)
    }
}

class ChatSelectionStorageTest {
    @Test
    fun relaySelectionRoundTrips() {
        val relay = ChatSelection.Relay("wss://png.communities.buzz.xyz")
        assertEquals(relay, decodeChatSelection(encodeChatSelection(relay)))
    }

    @Test
    fun allIsStoredAsNothing() {
        assertEquals(null, encodeChatSelection(ChatSelection.All))
        assertEquals(ChatSelection.All, decodeChatSelection(null))
        assertEquals(ChatSelection.All, decodeChatSelection(""))
        // 壊れた値は「すべて」に戻す。
        assertEquals(ChatSelection.All, decodeChatSelection("not a relay"))
    }

    @Test
    fun directSelectionRoundTrips() {
        assertEquals(ChatSelection.Direct, decodeChatSelection(encodeChatSelection(ChatSelection.Direct)))
    }
}

class ChatTabDmModeTest {
    private val relay = ChatRelaySummary("wss://relay.example", null)

    @Test
    fun withoutGroupsAllShowsDmsAndNoHeaderButton() {
        val state = ChatTabUiState(hasDm = true)
        assertEquals(true, state.showsDmList)
        assertEquals(false, state.showsDmButton)
    }

    @Test
    fun withGroupsAllShowsGroupsAndHeaderButtonOpensDms() {
        val state = ChatTabUiState(hasDm = true, relays = listOf(relay))
        assertEquals(false, state.showsDmList)
        assertEquals(true, state.showsDmButton)
        assertEquals(true, state.copy(selection = ChatSelection.Direct).showsDmList)
        assertEquals(false, state.copy(selection = ChatSelection.Relay(relay.relayUrl)).showsDmList)
    }

    @Test
    fun headerButtonIsOnlyShownForAllAndWhileDmsAreOpen() {
        // リレー・コミュニティを選んでいる間は、暗号化 DM のボタンを出さない。
        val state = ChatTabUiState(hasDm = true, relays = listOf(relay))
        assertEquals(true, state.copy(selection = ChatSelection.All).showsDmButton)
        assertEquals(false, state.copy(selection = ChatSelection.Relay(relay.relayUrl)).showsDmButton)
        // DM を開いている間は、閉じるためにボタンを残す。
        assertEquals(true, state.copy(selection = ChatSelection.Direct).showsDmButton)
    }

    @Test
    fun groupsRememberedFromLastTimeKeepTheGroupLayoutWhileLoading() {
        // 起動直後にグループの一覧（kind 10009）が読めていなくても、前回グループがあった人は DM が主役にならない。
        val state = ChatTabUiState(hasDm = true, groupsExpected = true)
        assertEquals(true, state.hasGroups)
        assertEquals(false, state.showsDmList)
        assertEquals(true, state.showsDmButton)
    }

    @Test
    fun fabInDmListOpensEncryptedDmDirectly() {
        val withGroups = ChatTabUiState(hasDm = true, hasSigner = true, relays = listOf(relay))
        assertEquals(ChatFabAction.NewEncryptedDm, withGroups.copy(selection = ChatSelection.Direct).fabAction)
        assertEquals(ChatFabAction.NewEncryptedDm, ChatTabUiState(hasDm = true, hasSigner = true).fabAction)
    }

    @Test
    fun fabOutsideDmListOpensGroupMenuWithoutEncryptedDm() {
        // 暗号化 DM は DM 側の＋から。グループ側の＋のメニューには出さない。
        val state = ChatTabUiState(hasDm = true, hasSigner = true, relays = listOf(relay))
        assertEquals(ChatFabAction.Menu, state.fabAction)
        assertEquals(ChatFabAction.Menu, state.copy(selection = ChatSelection.Relay(relay.relayUrl)).fabAction)
    }

    @Test
    fun fabWithoutCreateTargetOnlyJoins() {
        assertEquals(ChatFabAction.Join, ChatTabUiState(hasDm = false).fabAction)
        assertEquals(ChatFabAction.Join, ChatTabUiState(hasDm = true, groupsExpected = true, hasSigner = true).fabAction)
    }

    @Test
    fun signedOutHasNoDms() {
        val state = ChatTabUiState(hasDm = false)
        assertEquals(false, state.showsDmList)
        assertEquals(false, state.showsDmButton)
    }
}
