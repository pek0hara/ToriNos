package com.nostr.torinos.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class ChatAuthRelaysTest {
    @Test
    fun storedRelaysAreNormalizedAndDeduplicated() {
        assertEquals(
            setOf("wss://a.example", "wss://b.example"),
            decodeAuthRelays("wss://A.example/\nwss://b.example\n\nnot a url\nwss://a.example"),
        )
    }

    @Test
    fun missingValueIsEmpty() {
        assertEquals(emptySet(), decodeAuthRelays(null))
    }

    @Test
    fun encodingIsSortedOnePerLine() {
        assertEquals("wss://a.example\nwss://b.example", encodeAuthRelays(setOf("wss://b.example", "wss://a.example")))
    }

    @Test
    fun allowedRelaysIncludeJoinedGroupRelays() {
        val joined = listOf(JoinedGroup(GroupRef("wss://g.example", "x"), null))
        assertEquals(
            setOf("wss://a.example", "wss://g.example"),
            authAllowedRelays(consented = setOf("wss://a.example"), joined = joined),
        )
    }

    @Test
    fun groupsListIsRewrittenOnlyAfterAReliableLoad() {
        // 一部のリレーしか答えていないのに書き換えると、遅いリレーにある一覧を空の一覧で上書きしてしまう。
        assertEquals(false, groupsListRewriteAllowed(allRelaysAnswered = false, foundList = false))
        assertEquals(true, groupsListRewriteAllowed(allRelaysAnswered = true, foundList = false))
        assertEquals(true, groupsListRewriteAllowed(allRelaysAnswered = false, foundList = true))
    }

    @Test
    fun knownGroupsFollowAReliableLoadAndOtherwiseKeepThePreviousAnswer() {
        val group = listOf(JoinedGroup(GroupRef("wss://g.example", "x"), null))
        // 一覧が読めたら、その中身で決める。
        assertEquals(GroupsLoad.Known(true), groupsLoadOutcome(previous = false, allRelaysAnswered = false, found = group))
        assertEquals(GroupsLoad.Known(false), groupsLoadOutcome(previous = true, allRelaysAnswered = false, found = emptyList()))
        // 全リレーが「無い」と答えたら、無い。
        assertEquals(GroupsLoad.Known(false), groupsLoadOutcome(previous = true, allRelaysAnswered = true, found = null))
        // 答えがそろわず見つからなかったら、前回の控えのまま読み直す。
        assertEquals(GroupsLoad.Retry(true), groupsLoadOutcome(previous = true, allRelaysAnswered = false, found = null))
        assertEquals(GroupsLoad.Retry(false), groupsLoadOutcome(previous = false, allRelaysAnswered = false, found = null))
    }

    @Test
    fun knownGroupsFlagRoundTrips() {
        assertEquals(true, decodeKnownGroups(encodeKnownGroups(true)))
        assertEquals(false, decodeKnownGroups(encodeKnownGroups(false)))
        assertEquals(false, decodeKnownGroups(null))
    }

    @Test
    fun removingAPrivateEntryChangesThePrivatePart() {
        val ref = GroupRef("wss://g.example", "x")
        val private = listOf(listOf("group", "x", "wss://g.example"), listOf("r", "wss://g.example"))

        val result = applyGroupsListChange(
            publicTags = listOf(listOf("unknown", "1")),
            privateTags = private,
            change = GroupsListChange.Remove(ref),
        )

        assertEquals(listOf(listOf("unknown", "1")), result.publicTags)
        assertEquals(emptyList(), result.privateTags)
        assertEquals(true, result.privateChanged)
    }

    @Test
    fun addingGoesToThePublicPartOnly() {
        val group = JoinedGroup(GroupRef("wss://g.example", "x"), null)

        val result = applyGroupsListChange(emptyList(), listOf(listOf("group", "y", "wss://g.example")), GroupsListChange.Add(group))

        assertEquals(listOf(listOf("group", "x", "wss://g.example"), listOf("r", "wss://g.example")), result.publicTags)
        assertEquals(false, result.privateChanged)
    }

    @Test
    fun addingAGroupAlreadyListedPrivatelyChangesNothing() {
        val group = JoinedGroup(GroupRef("wss://g.example", "x"), null)

        val result = applyGroupsListChange(emptyList(), listOf(listOf("group", "x", "wss://g.example")), GroupsListChange.Add(group))

        assertEquals(emptyList(), result.publicTags)
        assertEquals(false, result.privateChanged)
    }

    @Test
    fun buzzMembershipUsesAuthFailureWhenTheClosedReasonIsGeneric() {
        // AUTH の拒否が REQ の拒否より先に届くと、閉じた理由は「未認証」になる。認証の失敗理由で見分ける。
        assertEquals(
            BuzzMembershipCheck.NotMember("restricted: not a relay member"),
            buzzMembershipFromClosed("auth-required: not authenticated", authFailure = "restricted: not a relay member"),
        )
        assertEquals(
            BuzzMembershipCheck.NotMember("auth-required: restricted: not a relay member"),
            buzzMembershipFromClosed("auth-required: restricted: not a relay member", authFailure = null),
        )
        // 署名の拒否や時間切れでは、メンバーでないとは決めない。
        assertEquals(
            BuzzMembershipCheck.Unknown("auth-required: not authenticated"),
            buzzMembershipFromClosed("auth-required: not authenticated", authFailure = "署名が拒否されました"),
        )
    }
}
