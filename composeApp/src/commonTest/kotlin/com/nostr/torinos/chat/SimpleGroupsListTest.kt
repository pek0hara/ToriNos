package com.nostr.torinos.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class SimpleGroupsListTest {
    private val relay = "wss://groups.example.com"

    @Test
    fun groupEntriesAreReadFromGroupTags() {
        val entries = parseJoinedGroups(
            listOf(
                listOf("group", "g1", "wss://Groups.Example.com/", "Nostr JP"),
                listOf("group", "g2", relay),
                listOf("group", "", relay),
                listOf("group", "g3"),
                listOf("r", relay),
                listOf("unknown", "x"),
            ),
        )

        assertEquals(
            listOf(
                JoinedGroup(GroupRef(relay, "g1"), "Nostr JP"),
                JoinedGroup(GroupRef(relay, "g2"), null),
            ),
            entries,
        )
    }

    @Test
    fun duplicatesKeepTheFirstEntry() {
        val entries = parseJoinedGroups(
            listOf(listOf("group", "g1", relay, "a"), listOf("group", "g1", "$relay/", "b")),
        )
        assertEquals(listOf(JoinedGroup(GroupRef(relay, "g1"), "a")), entries)
    }

    @Test
    fun addingKeepsUnknownTagsAndAddsRelayTag() {
        val tags = listOf(listOf("unknown", "x"), listOf("group", "g0", relay))

        val next = withJoinedGroup(tags, JoinedGroup(GroupRef("wss://other.example", "g1"), "名前"))

        assertEquals(
            listOf(
                listOf("unknown", "x"),
                listOf("group", "g0", relay),
                listOf("group", "g1", "wss://other.example", "名前"),
                listOf("r", "wss://other.example"),
            ),
            next,
        )
    }

    @Test
    fun addingAnExistingGroupChangesNothing() {
        val tags = listOf(listOf("group", "g1", relay, "a"), listOf("r", relay))
        assertEquals(tags, withJoinedGroup(tags, JoinedGroup(GroupRef(relay, "g1"), "b")))
    }

    @Test
    fun removingDropsGroupAndUnusedRelayTag() {
        val tags = listOf(
            listOf("group", "g1", relay),
            listOf("group", "g2", "wss://other.example"),
            listOf("r", relay),
            listOf("r", "wss://other.example"),
            listOf("unknown", "x"),
        )

        val next = withoutJoinedGroup(tags, GroupRef(relay, "g1"))

        assertEquals(
            listOf(
                listOf("group", "g2", "wss://other.example"),
                listOf("r", "wss://other.example"),
                listOf("unknown", "x"),
            ),
            next,
        )
    }

    @Test
    fun removingKeepsRelayTagStillUsedByAnotherGroup() {
        val tags = listOf(listOf("group", "g1", relay), listOf("group", "g2", relay), listOf("r", relay))

        assertEquals(
            listOf(listOf("group", "g2", relay), listOf("r", relay)),
            withoutJoinedGroup(tags, GroupRef(relay, "g1")),
        )
    }
}
