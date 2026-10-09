package com.nostr.torinos.ui.channel

import com.nostr.torinos.model.ChannelMeta
import com.nostr.torinos.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals

class ChannelListSortTest {
    @Test
    fun favoritesComeFirstThenNewestActivity() {
        val sorted = listOf(
            item("old", lastActivityAt = 10),
            item("favOld", lastActivityAt = 5, isFavorite = true),
            item("new", lastActivityAt = 30),
            item("favNew", lastActivityAt = 20, isFavorite = true),
        ).sortedForChannelList()

        assertEquals(listOf("favNew", "favOld", "new", "old"), sorted.map { it.event.id })
    }

    @Test
    fun channelsWithoutActivityFollowThoseWithActivityWithinEachGroup() {
        val sorted = listOf(
            item("favQuiet", createdAt = 100, isFavorite = true),
            item("quiet", createdAt = 100),
            item("active", lastActivityAt = 10),
            item("favActive", lastActivityAt = 1, isFavorite = true),
        ).sortedForChannelList()

        assertEquals(listOf("favActive", "favQuiet", "active", "quiet"), sorted.map { it.event.id })
    }

    @Test
    fun sortUsesProvidedFavoriteFlagSoToggledRowsStayInPlace() {
        val toggled = item("toggled", lastActivityAt = 5, isFavorite = true)
        val sorted = listOf(
            item("fav", lastActivityAt = 1, isFavorite = true),
            toggled,
            item("new", lastActivityAt = 10),
        ).sortedForChannelList { if (it === toggled) false else it.isFavorite }

        assertEquals(listOf("fav", "new", "toggled"), sorted.map { it.event.id })
    }

    private fun item(
        id: String,
        lastActivityAt: Long? = null,
        createdAt: Long = 0,
        isFavorite: Boolean = false,
    ) = ChannelItem(
        event = NostrEvent(id = id, pubkey = "p", createdAt = createdAt, kind = 40, tags = emptyList(), content = "", sig = ""),
        meta = ChannelMeta(),
        lastActivityAt = lastActivityAt,
        isFavorite = isFavorite,
    )
}
