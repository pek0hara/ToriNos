package com.nostr.torinos

import androidx.compose.ui.unit.dp
import com.nostr.torinos.ui.service.ServiceTab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FooterConfigTest {
    private val defaultOrder = listOf(
        FooterItem.Home,
        FooterItem.Journal,
        FooterItem.Articles,
        FooterItem.Channels,
        FooterItem.Status,
        FooterItem.Emojis,
    )

    @Test
    fun defaultShowsHomeJournalArticlesAndChannels() {
        assertEquals(defaultOrder, FooterConfig.Default.order)
        assertEquals(
            listOf(FooterItem.Home, FooterItem.Journal, FooterItem.Articles, FooterItem.Channels),
            FooterConfig.Default.visibleItems,
        )
        assertEquals(setOf(FooterItem.Status, FooterItem.Emojis), FooterConfig.Default.hidden)
    }

    @Test
    fun missingItemsKeepTheirDefaultVisibility() {
        val config = FooterConfig.parse("channels,home")

        assertEquals(
            listOf(
                FooterItem.Channels,
                FooterItem.Home,
                FooterItem.Journal,
                FooterItem.Articles,
                FooterItem.Status,
                FooterItem.Emojis,
            ),
            config.order,
        )
        assertEquals(setOf(FooterItem.Status, FooterItem.Emojis), config.hidden)
    }

    @Test
    fun storedVisibilityOverridesDefault() {
        val config = FooterConfig.parse("home,journal,articles,channels,status,emojis")

        assertTrue(config.isVisible(FooterItem.Status))
        assertTrue(config.isVisible(FooterItem.Emojis))
    }

    @Test
    fun serializeRoundTripsOrderAndHiddenItems() {
        val config = FooterConfig(
            order = listOf(
                FooterItem.Home,
                FooterItem.Articles,
                FooterItem.Journal,
                FooterItem.Channels,
                FooterItem.Status,
                FooterItem.Emojis,
            ),
            hidden = setOf(FooterItem.Journal),
        )
        val stored = config.serialize()

        assertEquals("home,articles,!journal,channels,status,emojis", stored)
        assertEquals(config, FooterConfig.parse(stored))
    }

    @Test
    fun parseFallsBackToDefaultForMissingOrBrokenValue() {
        assertEquals(FooterConfig.Default, FooterConfig.parse(null))
        assertEquals(FooterConfig.Default, FooterConfig.parse(""))
        assertEquals(FooterConfig.Default, FooterConfig.parse(",,!,unknown"))
    }

    @Test
    fun parseIgnoresUnknownAndDuplicateIdsAndAppendsMissingItems() {
        val config = FooterConfig.parse("emojis,unknown,status,emojis,!status")

        assertEquals(
            listOf(
                FooterItem.Emojis,
                FooterItem.Status,
                FooterItem.Home,
                FooterItem.Journal,
                FooterItem.Articles,
                FooterItem.Channels,
            ),
            config.order,
        )
        assertEquals(emptySet<FooterItem>(), config.hidden)
    }

    @Test
    fun homeIsAlwaysVisible() {
        assertTrue(FooterConfig.parse("!home,journal").isVisible(FooterItem.Home))
        assertEquals(FooterConfig.Default, FooterConfig.Default.toggleVisible(FooterItem.Home))
    }

    @Test
    fun toggleVisibleHidesAndShowsItem() {
        val hidden = FooterConfig.Default.toggleVisible(FooterItem.Channels)

        assertFalse(hidden.isVisible(FooterItem.Channels))
        assertEquals(listOf(FooterItem.Home, FooterItem.Journal, FooterItem.Articles), hidden.visibleItems)
        assertEquals(FooterConfig.Default, hidden.toggleVisible(FooterItem.Channels))

        val shown = FooterConfig.Default.toggleVisible(FooterItem.Emojis)
        assertTrue(shown.isVisible(FooterItem.Emojis))
    }

    @Test
    fun moveSwapsWithNeighborAndStopsAtEnds() {
        val movedUp = FooterConfig.Default.move(FooterItem.Articles, -1)
        assertEquals(
            listOf(
                FooterItem.Home,
                FooterItem.Articles,
                FooterItem.Journal,
                FooterItem.Channels,
                FooterItem.Status,
                FooterItem.Emojis,
            ),
            movedUp.order,
        )
        assertEquals(FooterConfig.Default, movedUp.move(FooterItem.Articles, 1))
        assertEquals(FooterConfig.Default, FooterConfig.Default.move(FooterItem.Home, -1))
        assertEquals(FooterConfig.Default, FooterConfig.Default.move(FooterItem.Emojis, 1))
    }

    @Test
    fun hiddenStatePersistsThroughMove() {
        val config = FooterConfig.Default.toggleVisible(FooterItem.Channels).move(FooterItem.Channels, -1)

        assertFalse(config.isVisible(FooterItem.Channels))
        assertEquals(FooterConfig.parse(config.serialize()), config)
    }

    @Test
    fun servicePagesFollowFooterOrderWithoutHiddenItems() {
        val config = FooterConfig(
            order = listOf(
                FooterItem.Home,
                FooterItem.Status,
                FooterItem.Journal,
                FooterItem.Articles,
                FooterItem.Channels,
                FooterItem.Emojis,
            ),
            hidden = setOf(FooterItem.Channels),
        )

        assertEquals(listOf(ServiceTab.Status, ServiceTab.Articles, ServiceTab.Emojis), config.serviceTabs)
    }

    @Test
    fun resolveServiceTabKeepsVisibleTabAndFallsBackToFirstVisible() {
        val pages = listOf(ServiceTab.Status, ServiceTab.Emojis)

        assertEquals(ServiceTab.Emojis, resolveServiceTab(ServiceTab.Emojis, pages))
        assertEquals(ServiceTab.Status, resolveServiceTab(ServiceTab.Channels, pages))
        assertNull(resolveServiceTab(ServiceTab.Channels, emptyList()))
    }

    @Test
    fun itemsShareWidthEquallyWhenTheyFit() {
        val layout = footerItemLayout(maxWidth = 480.dp, itemCount = 6)

        assertEquals(80.dp, layout.itemWidth)
        assertFalse(layout.scrollable)
    }

    @Test
    fun overflowingItemsLeaveHalfOfTheNextItemVisible() {
        val narrow = footerItemLayout(maxWidth = 360.dp, itemCount = 6)
        assertEquals(80.dp, narrow.itemWidth)
        assertTrue(narrow.scrollable)

        val iphone = footerItemLayout(maxWidth = 390.dp, itemCount = 6)
        assertEquals(390f / 4.5f, iphone.itemWidth.value, absoluteTolerance = 0.01f)
        assertTrue(iphone.scrollable)
    }

    @Test
    fun veryNarrowBarStillShowsMoreThanOneItem() {
        val layout = footerItemLayout(maxWidth = 100.dp, itemCount = 6)

        assertEquals(100f / 1.5f, layout.itemWidth.value, absoluteTolerance = 0.01f)
        assertTrue(layout.scrollable)
    }

    @Test
    fun singleOrNoItemFillsTheBar() {
        assertEquals(360.dp, footerItemLayout(maxWidth = 360.dp, itemCount = 1).itemWidth)
        assertFalse(footerItemLayout(maxWidth = 360.dp, itemCount = 0).scrollable)
    }
}
