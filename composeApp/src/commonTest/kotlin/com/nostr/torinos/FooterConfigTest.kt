package com.nostr.torinos

import androidx.compose.ui.unit.dp
import com.nostr.torinos.ui.service.ServiceTab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FooterConfigTest {
    private val defaultBar = listOf(FooterItem.Feed, FooterItem.Journal, FooterItem.Chat)
    private val defaultMenu =
        listOf(FooterItem.Channels, FooterItem.Articles, FooterItem.Emojis, FooterItem.Status, FooterItem.Settings)

    /** 設定はメニューの末尾に固定なので、メニューへ足した項目は設定の手前に入る。 */
    private fun defaultMenuPlus(vararg items: FooterItem) = defaultMenu.dropLast(1) + items + FooterItem.Settings

    @Test
    fun defaultPutsFeedJournalChatInTheBarAndTheRestInTheMenu() {
        assertEquals(defaultBar, FooterConfig.Default.barItems)
        assertEquals(defaultMenu, FooterConfig.Default.menuItems)
    }

    @Test
    fun togglePlacementMovesItemToTheEndOfTheOtherSide() {
        val config = FooterConfig.Default.togglePlacement(FooterItem.Journal)
        assertEquals(listOf(FooterItem.Feed, FooterItem.Chat), config.barItems)
        assertEquals(defaultMenuPlus(FooterItem.Journal), config.menuItems)

        val back = config.togglePlacement(FooterItem.Articles)
        assertEquals(listOf(FooterItem.Feed, FooterItem.Chat, FooterItem.Articles), back.barItems)
        assertEquals(
            listOf(FooterItem.Channels, FooterItem.Emojis, FooterItem.Status, FooterItem.Journal, FooterItem.Settings),
            back.menuItems,
        )
    }

    @Test
    fun feedStaysInTheBar() {
        assertFalse(FooterItem.Feed.canMoveToMenu)
        assertEquals(FooterConfig.Default, FooterConfig.Default.togglePlacement(FooterItem.Feed))
        assertEquals(listOf(FooterItem.Feed), FooterConfig.parse("v2:~home,~journal").barItems.take(1))
        assertFalse(FooterConfig.parse("v2:~home").isInMenu(FooterItem.Feed))
    }

    @Test
    fun moveSwapsWithinTheSameSideAndStopsAtEnds() {
        val bar = FooterConfig.Default.move(FooterItem.Chat, -1)
        assertEquals(listOf(FooterItem.Feed, FooterItem.Chat, FooterItem.Journal), bar.barItems)
        assertEquals(defaultMenu, bar.menuItems)
        assertEquals(FooterConfig.Default, bar.move(FooterItem.Chat, 1))

        val menu = FooterConfig.Default.move(FooterItem.Channels, 1)
        assertEquals(
            listOf(FooterItem.Articles, FooterItem.Channels, FooterItem.Emojis, FooterItem.Status, FooterItem.Settings),
            menu.menuItems,
        )
        assertEquals(defaultBar, menu.barItems)

        assertEquals(FooterConfig.Default, FooterConfig.Default.move(FooterItem.Feed, -1))
        assertEquals(FooterConfig.Default, FooterConfig.Default.move(FooterItem.Chat, 1))
        assertEquals(FooterConfig.Default, FooterConfig.Default.move(FooterItem.Channels, -1))
        assertEquals(FooterConfig.Default, FooterConfig.Default.move(FooterItem.Settings, 1))
    }

    @Test
    fun settingsStaysLastInTheMenu() {
        // 並べ替えでは動かず、隣の項目も設定の後ろへは行かない。
        assertEquals(FooterConfig.Default, FooterConfig.Default.move(FooterItem.Settings, -1))
        assertEquals(FooterConfig.Default, FooterConfig.Default.move(FooterItem.Status, 1))
        assertFalse(FooterConfig.Default.canMove(FooterItem.Settings, -1))
        assertFalse(FooterConfig.Default.canMove(FooterItem.Status, 1))
        assertTrue(FooterConfig.Default.canMove(FooterItem.Status, -1))
        // 保存値で途中にあっても末尾に置き直す。
        assertEquals(
            listOf(FooterItem.Channels, FooterItem.Articles, FooterItem.Emojis, FooterItem.Status, FooterItem.Settings),
            FooterConfig.parse("v2:home,journal,chat,~settings,~channels,~articles,~emojis,~status").menuItems,
        )
    }

    @Test
    fun settingsMovedToTheBarCanBeReordered() {
        val config = FooterConfig.Default.togglePlacement(FooterItem.Settings)
        assertEquals(defaultBar + FooterItem.Settings, config.barItems)

        val moved = config.move(FooterItem.Settings, -1)
        assertEquals(listOf(FooterItem.Feed, FooterItem.Journal, FooterItem.Settings, FooterItem.Chat), moved.barItems)
    }

    @Test
    fun serializeRoundTripsOrderAndPlacement() {
        val config = FooterConfig.Default
            .togglePlacement(FooterItem.Chat)
            .togglePlacement(FooterItem.Articles)
            .move(FooterItem.Articles, -1)
        val stored = config.serialize()

        assertEquals("v2:home,articles,journal,~channels,~emojis,~status,~chat,~settings", stored)
        assertEquals(config, FooterConfig.parse(stored))
    }

    @Test
    fun parseFallsBackToDefaultForMissingOrBrokenValue() {
        assertEquals(FooterConfig.Default, FooterConfig.parse(null))
        assertEquals(FooterConfig.Default, FooterConfig.parse(""))
        assertEquals(FooterConfig.Default, FooterConfig.parse(",,!,unknown"))
        assertEquals(FooterConfig.Default, FooterConfig.parse("v2:,,~,unknown"))
    }

    @Test
    fun parseIgnoresUnknownAndDuplicateIdsAndAppendsMissingItemsOnTheirDefaultSide() {
        val config = FooterConfig.parse("v2:~chat,unknown,emojis,~chat,home")

        assertEquals(listOf(FooterItem.Emojis, FooterItem.Feed, FooterItem.Journal), config.barItems)
        assertEquals(
            listOf(FooterItem.Chat, FooterItem.Channels, FooterItem.Articles, FooterItem.Status, FooterItem.Settings),
            config.menuItems,
        )
    }

    @Test
    fun legacyValuePutsServicesAndHiddenItemsInTheMenu() {
        // メニューが無かった頃の保存値。「!」は非表示。隠していた項目もメニューから開けるようにする。
        val config = FooterConfig.parse("chat,home,!journal,articles,channels,!status,!emojis")

        assertEquals(listOf(FooterItem.Chat, FooterItem.Feed), config.barItems)
        assertEquals(defaultMenuPlus(FooterItem.Journal), config.menuItems)
    }

    @Test
    fun legacyValueWithoutChatKeepsChatInTheBar() {
        val config = FooterConfig.parse("home,journal,articles,channels,!status,!emojis")

        assertEquals(defaultBar, config.barItems)
        assertEquals(defaultMenu, config.menuItems)
    }

    @Test
    fun everythingButFeedCanLeaveTheMenuEmpty() {
        val config = defaultMenu.fold(FooterConfig.Default) { acc, item -> acc.togglePlacement(item) }

        assertEquals(emptyList(), config.menuItems)
        assertEquals(defaultBar + defaultMenu, config.barItems)
        assertEquals(config, FooterConfig.parse(config.serialize()))
    }

    @Test
    fun settingsIsNeverTheLaunchScreen() {
        assertEquals(FooterItem.Feed, FooterConfig.Default.launchItem(FooterItem.Settings))
        assertNull(footerItemOf("settings", ServiceTab.Chat))
    }

    @Test
    fun legacyFeedIsNeverHidden() {
        assertFalse(FooterConfig.parse("!home,journal").isInMenu(FooterItem.Feed))
    }

    @Test
    fun servicePagesIncludeBothSides() {
        val config = FooterConfig.Default.togglePlacement(FooterItem.Chat)

        assertEquals(
            listOf(ServiceTab.Channels, ServiceTab.Articles, ServiceTab.Emojis, ServiceTab.Status, ServiceTab.Chat),
            config.serviceTabs,
        )
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

    @Test
    fun feedKeepsTheStoredIdOfFormerHome() {
        // 旧名「ホーム」の保存値をそのまま読めるようにする。
        assertEquals(FooterItem.Feed, FooterItem.fromStorageId("home"))
        assertEquals("フィード", FooterItem.Feed.label)
    }

    @Test
    fun launchItemIsTheLastOpenedTab() {
        assertEquals(FooterItem.Channels, FooterConfig.Default.launchItem(FooterItem.Channels))
        assertEquals(FooterItem.Journal, FooterConfig.Default.launchItem(FooterItem.Journal))
        // メニューへ移したタブも開き直す。
        assertEquals(FooterItem.Chat, FooterConfig.Default.togglePlacement(FooterItem.Chat).launchItem(FooterItem.Chat))
    }

    @Test
    fun launchItemFallsBackToFeed() {
        assertEquals(FooterItem.Feed, FooterConfig.parse("v2:chat,journal,home").launchItem(null))
    }

    @Test
    fun footerItemOfTopLevelScreens() {
        assertEquals(FooterItem.Feed, footerItemOf("feed", ServiceTab.Chat))
        assertEquals(FooterItem.Journal, footerItemOf("journal", ServiceTab.Chat))
        assertEquals(FooterItem.Chat, footerItemOf("services", ServiceTab.Chat))
        assertEquals(FooterItem.Articles, footerItemOf("services", ServiceTab.Articles))
        // タブに属さない画面（設定・詳細など）は記録しない。
        assertNull(footerItemOf("settings", ServiceTab.Chat))
        assertNull(footerItemOf(null, ServiceTab.Chat))
    }

    @Test
    fun routeOfEachItem() {
        assertEquals("feed", FooterItem.Feed.route)
        assertEquals("journal", FooterItem.Journal.route)
        assertEquals("services", FooterItem.Chat.route)
    }
}
