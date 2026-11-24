package com.nostr.torinos.chat

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/buzz-community-sync-design.md の 10 章。 */
class BuzzCommunitySyncTest {
    private val buzzA = "wss://a.buzz.example"
    private val buzzB = "wss://b.buzz.example"
    private val groups = "wss://groups.example"

    // ---- 1. 候補の取り出し ----

    @Test
    fun candidatesAreEncryptedRelayTagsWithoutGroups() {
        val candidates = buzzRelayCandidates(
            publicTags = listOf(listOf("group", "g1", groups), listOf("r", groups), listOf("r", buzzB)),
            privateTags = listOf(
                listOf("r", "wss://A.buzz.example/"),
                listOf("r", buzzA),
                listOf("r", groups),
                listOf("r", "not a url"),
                listOf("r"),
                listOf("unknown", buzzA),
            ),
        )
        // 公開タグの r は所属を隠していないので候補にしない。標準グループのリレーの r も除く。
        assertEquals(setOf(buzzA), candidates)
    }

    @Test
    fun encryptedGroupMakesItsRelayAStandardRelay() {
        val candidates = buzzRelayCandidates(
            publicTags = emptyList(),
            privateTags = listOf(listOf("group", "g1", groups), listOf("r", groups), listOf("r", buzzA)),
        )
        assertEquals(setOf(buzzA), candidates)
    }

    // ---- 2. 合わせ方 ----

    @Test
    fun remoteOnlyIsVerifiedAndLocalOnlyIsPublished() {
        val plan = planBuzzSync(local = setOf(buzzA), remote = setOf(buzzB), notMember = emptyMap(), listId = "v1")
        assertEquals(setOf(buzzB), plan.toVerify)
        assertEquals(setOf(buzzA), plan.toPublish)
    }

    @Test
    fun nothingToDoWhenBothAgree() {
        val plan = planBuzzSync(local = setOf(buzzA), remote = setOf(buzzA), notMember = emptyMap(), listId = "v1")
        assertEquals(BuzzSyncPlan(emptySet(), emptySet()), plan)
    }

    @Test
    fun noListPublishesEverythingLocal() {
        // 全リレーが「無い」と答えた（一覧がまだ無い）ときは、端末の分をすべて書く。
        val plan = planBuzzSync(local = setOf(buzzA, buzzB), remote = emptySet(), notMember = emptyMap(), listId = null)
        assertEquals(emptySet(), plan.toVerify)
        assertEquals(setOf(buzzA, buzzB), plan.toPublish)
    }

    @Test
    fun notMemberIsNotRecheckedUntilTheListChanges() {
        val notMember = mapOf(buzzB to "v1")
        assertEquals(emptySet(), planBuzzSync(emptySet(), setOf(buzzB), notMember, listId = "v1").toVerify)
        assertEquals(setOf(buzzB), planBuzzSync(emptySet(), setOf(buzzB), notMember, listId = "v2").toVerify)
    }

    @Test
    fun notMemberEntriesAreNotPublishedBackOrRemoved() {
        // メンバーでないと分かったものは端末に無いので、書き足しにも出ない（10009 からも消さない）。
        val plan = planBuzzSync(local = emptySet(), remote = setOf(buzzB), notMember = mapOf(buzzB to "v1"), listId = "v1")
        assertEquals(BuzzSyncPlan(emptySet(), emptySet()), plan)
    }

    @Test
    fun syncStateRoundTrips() {
        val state = BuzzSyncState(listId = "abc", remote = setOf(buzzB, buzzA))
        assertEquals(state, decodeBuzzSyncState(encodeBuzzSyncState(state)))
        assertNull(decodeBuzzSyncState(null))
        assertNull(decodeBuzzSyncState("broken"))
    }

    @Test
    fun notMemberMapRoundTrips() {
        val map = mapOf(buzzA to "v1", buzzB to "v2")
        assertEquals(map, decodeBuzzNotMember(encodeBuzzNotMember(map)))
        assertEquals(emptyMap(), decodeBuzzNotMember(null))
    }

    // ---- 3. 書き換え ----

    @Test
    fun addingPrivateRelaysKeepsUnknownTagsAndSkipsExisting() {
        val update = applyGroupsListChange(
            publicTags = listOf(listOf("group", "g1", groups), listOf("r", groups)),
            privateTags = listOf(listOf("x", "secret"), listOf("r", buzzA)),
            change = GroupsListChange.AddPrivateRelays(setOf(buzzA, buzzB)),
        )
        assertEquals(listOf(listOf("group", "g1", groups), listOf("r", groups)), update.publicTags)
        assertEquals(listOf(listOf("x", "secret"), listOf("r", buzzA), listOf("r", buzzB)), update.privateTags)
        assertTrue(update.privateChanged)
    }

    @Test
    fun addingAlreadyListedRelaysChangesNothing() {
        val update = applyGroupsListChange(
            publicTags = emptyList(),
            privateTags = listOf(listOf("r", "$buzzA/")),
            change = GroupsListChange.AddPrivateRelays(setOf(buzzA)),
        )
        assertFalse(update.privateChanged)
    }

    @Test
    fun addedRelaysAreWrittenInAStableOrder() {
        val update = applyGroupsListChange(emptyList(), emptyList(), GroupsListChange.AddPrivateRelays(setOf(buzzB, buzzA)))
        assertEquals(listOf(listOf("r", buzzA), listOf("r", buzzB)), update.privateTags)
    }

    // ---- 4. 復号に失敗したら書き換えない ----

    @Test
    fun blankContentStartsEmpty() = runTest {
        assertEquals(emptyList(), readPrivateTagsStrict("") { error("呼ばれない") })
    }

    @Test
    fun decryptedTagsAreRead() = runTest {
        assertEquals(listOf(listOf("r", buzzA)), readPrivateTagsStrict("cipher") { """[["r","$buzzA"]]""" })
    }

    @Test
    fun failedDecryptionIsNotAnEmptyList() = runTest {
        assertNull(readPrivateTagsStrict("cipher") { throw IllegalStateException("rejected") })
        assertNull(readPrivateTagsStrict("cipher") { "not json" })
    }

    // ---- 5. 標準グループを外しても Buzz の r は残す ----

    @Test
    fun removingAGroupKeepsBuzzRelayTag() {
        val update = applyGroupsListChange(
            publicTags = emptyList(),
            privateTags = listOf(listOf("group", "g1", buzzA), listOf("r", buzzA)),
            change = GroupsListChange.Remove(GroupRef(buzzA, "g1"), keepRelays = setOf(buzzA)),
        )
        assertEquals(listOf(listOf("r", buzzA)), update.privateTags)
    }

    @Test
    fun removingAGroupStillDropsItsOwnRelayTag() {
        val update = applyGroupsListChange(
            publicTags = listOf(listOf("group", "g1", groups), listOf("r", groups)),
            privateTags = emptyList(),
            change = GroupsListChange.Remove(GroupRef(groups, "g1"), keepRelays = setOf(buzzA)),
        )
        assertEquals(emptyList(), update.publicTags)
    }

    // ---- 6. 一覧が確かでないときは合わせない ----

    @Test
    fun syncRunsOnlyAfterAReliableLoad() {
        assertFalse(buzzSyncAllowed(listConfirmed = false))
        assertTrue(buzzSyncAllowed(listConfirmed = true))
    }

    // ---- 7. 確認中のリレーへの AUTH ----

    @Test
    fun restoringRelaysMayAuthenticate() {
        assertEquals(
            setOf(buzzA, buzzB),
            authAllowedRelays(consented = emptySet(), joined = emptyList(), buzzCommunities = setOf(buzzA), restoring = setOf(buzzB)),
        )
        // 確認が終わって確認中から外れたら、許されない。
        assertEquals(setOf(buzzA), authAllowedRelays(consented = emptySet(), joined = emptyList(), buzzCommunities = setOf(buzzA)))
    }

    // ---- 8. 外部署名器で確認を出す回数 ----

    @Test
    fun sameListVersionIsNotDecryptedAgain() {
        val stored = BuzzSyncState("v1", setOf(buzzA))
        assertFalse(needsBuzzDecrypt(listId = "v1", contentBlank = false, stored = stored))
        assertTrue(needsBuzzDecrypt(listId = "v2", contentBlank = false, stored = stored))
        assertTrue(needsBuzzDecrypt(listId = "v1", contentBlank = false, stored = null))
        // 暗号化項目が無い、一覧が無いときは復号しない。
        assertFalse(needsBuzzDecrypt(listId = "v2", contentBlank = true, stored = stored))
        assertFalse(needsBuzzDecrypt(listId = null, contentBlank = true, stored = null))
    }
}

/** 参加中のコミュニティから退会させられたのを、AUTH の拒否で知る（docs/buzz-community-sync-design.md 14 章）。 */
class BuzzRemovedDetectionTest {
    private val me = "a".repeat(64)
    private val other = "b".repeat(64)
    private val png = "wss://png.buzz.example"
    private val notMember = "restricted: not a relay member"

    private fun failed(gen: Long, reason: String = notMember, pubkey: String = me) =
        com.nostr.torinos.network.RelayAuthState.Failed(gen, pubkey, reason)

    @Test
    fun newNotMemberRefusalOnJoinedCommunityIsRemoval() {
        val removed = buzzCommunitiesRemovedByRelay(
            previous = mapOf(png to com.nostr.torinos.network.RelayAuthState.Authenticated(1, me)),
            current = mapOf(png to failed(2)),
            joined = setOf(png),
            pubkey = me,
            checking = emptySet(),
        )
        assertEquals(setOf(png), removed)
    }

    @Test
    fun otherRefusalsAreNotRemoval() {
        // 署名の拒否・時間切れ・リレーの内部エラーは、メンバーでなくなったとは決めない。
        listOf("error: internal error checking relay membership", "signer rejected", "auth-required: timeout").forEach { reason ->
            val removed = buzzCommunitiesRemovedByRelay(emptyMap(), mapOf(png to failed(2, reason)), setOf(png), me, emptySet())
            assertTrue(removed.isEmpty(), reason)
        }
    }

    @Test
    fun staleRefusalIsNotRemovalAfterRejoining() {
        // 招待で参加し直す前の拒否が残っていても、同じ状態のままなら外さない（参加直後に消さない）。
        val stale = mapOf(png to failed(2))
        assertTrue(buzzCommunitiesRemovedByRelay(stale, stale, setOf(png), me, emptySet()).isEmpty())
    }

    @Test
    fun notJoinedOrOtherAccountOrCheckingIsIgnored() {
        assertTrue(buzzCommunitiesRemovedByRelay(emptyMap(), mapOf(png to failed(2)), emptySet(), me, emptySet()).isEmpty())
        assertTrue(buzzCommunitiesRemovedByRelay(emptyMap(), mapOf(png to failed(2, pubkey = other)), setOf(png), me, emptySet()).isEmpty())
        // 参加の確認中（招待なしで入ろうとした）の拒否は、確認する側が扱う。
        assertTrue(buzzCommunitiesRemovedByRelay(emptyMap(), mapOf(png to failed(2)), setOf(png), me, setOf(png)).isEmpty())
    }
}
