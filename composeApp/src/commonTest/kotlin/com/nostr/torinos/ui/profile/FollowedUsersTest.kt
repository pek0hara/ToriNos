package com.nostr.torinos.ui.profile

import com.nostr.torinos.model.NostrProfile
import kotlin.test.Test
import kotlin.test.assertEquals

class FollowedUsersTest {
    @Test
    fun usersAreSortedByNameWithUnknownProfilesLast() {
        val profiles = mapOf(
            "b" to NostrProfile(name = "bob"),
            "a" to NostrProfile(displayName = "Alice"),
            "c" to NostrProfile(name = "  "),
        )

        val result = sortFollowedUsers(listOf("z", "b", "c", "a"), profiles)

        assertEquals(listOf("a", "b", "c", "z"), result)
    }

    @Test
    fun usersCanBeFilteredByNameOrNip05() {
        val profiles = mapOf(
            "a" to NostrProfile(displayName = "かもめ", name = "kamome"),
            "b" to NostrProfile(name = "tori", nip05 = "tori@example.com"),
        )
        val users = listOf("a", "b", "c")

        assertEquals(listOf("a"), filterFollowedUsers(users, profiles, "KAMO"))
        assertEquals(listOf("b"), filterFollowedUsers(users, profiles, "example.com"))
        assertEquals(users, filterFollowedUsers(users, profiles, " "))
    }
}
