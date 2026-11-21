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
        FooterItem.Feed,
        FooterItem.Journal,
        FooterItem.Articles,
        FooterItem.Channels,
        FooterItem.Status,
        FooterItem.Emojis,
        FooterItem.Chat,
    )

    @Test
    fun chatIsShownByDefault() {
        // 招待リンクを持ってきた人が参加の場所を見つけられるよう、最初から出す。
        assertTrue(FooterConfig.Default.isVisible(FooterItem.Chat))
        assertEquals(ServiceTab.Chat, FooterConfig.Default.serviceTabs.last())
    }

    @Test
    fun chatHiddenByTheUserStaysHiddenAndCanBeShownAgain() {
        val hidden = FooterConfig.parse("home,journal,articles,channels,!status,!emojis,!chat")
        assertFalse(hidden.isVisible(FooterItem.Chat))
        val shown = hidden.show(FooterItem.Chat)
        assertTrue(shown.isVisible(FooterItem.Chat))
        assertEquals(shown, shown.show(FooterItem.Chat))
    }

    @Test
    fun storedConfigFromBeforeChatShowsChat() {
        // チャットが無かった版の保存値には chat が無い。既定どおり表示にする。
        val config = FooterConfig.parse("home,!journal,articles,channels,!status,!emojis")
        assertTrue(config.isVisible(FooterItem.Chat))
        assertFalse(config.isVisible(FooterItem.Journal))
    }

    @Test
    fun defaultShowsHomeJournalArticlesChannelsAndChat() {
        assertEquals(defaultOrder, FooterConfig.Default.order)
        assertEquals(
            listOf(FooterItem.Feed, FooterItem.Journal, FooterItem.Articles, FooterItem.Channels, FooterItem.Chat),
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
                FooterItem.Feed,
                FooterItem.Journal,
                FooterItem.Articles,
                FooterItem.Status,
                FooterItem.Emojis,
                FooterItem.Chat,
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
                FooterItem.Feed,
                FooterItem.Articles,
                FooterItem.Journal,
                FooterItem.Channels,
                FooterItem.Status,
                FooterItem.Emojis,
                FooterItem.Chat,
            ),
            hidden = setOf(FooterItem.Journal, FooterItem.Chat),
        )
        val stored = config.serialize()

        assertEquals("home,articles,!journal,channels,status,emojis,!chat", stored)
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
                FooterItem.Feed,
                FooterItem.Journal,
                FooterItem.Articles,
                FooterItem.Channels,
                FooterItem.Chat,
            ),
            config.order,
        )
        // 保存値に無い項目は既定の表示状態で足す(チャットは既定で表示)。
        assertEquals(emptySet(), config.hidden)
    }

    @Test
    fun homeIsAlwaysVisible() {
        assertTrue(FooterConfig.parse("!home,journal").isVisible(FooterItem.Feed))
        assertEquals(FooterConfig.Default, FooterConfig.Default.toggleVisible(FooterItem.Feed))
    }

    @Test
    fun toggleVisibleHidesAndShowsItem() {
        val hidden = FooterConfig.Default.toggleVisible(FooterItem.Channels)

        assertFalse(hidden.isVisible(FooterItem.Channels))
        assertEquals(listOf(FooterItem.Feed, FooterItem.Journal, FooterItem.Articles, FooterItem.Chat), hidden.visibleItems)
        assertEquals(FooterConfig.Default, hidden.toggleVisible(FooterItem.Channels))

        val shown = FooterConfig.Default.toggleVisible(FooterItem.Emojis)
        assertTrue(shown.isVisible(FooterItem.Emojis))
    }

    @Test
    fun moveSwapsWithNeighborAndStopsAtEnds() {
        val movedUp = FooterConfig.Default.move(FooterItem.Articles, -1)
        assertEquals(
            listOf(
                FooterItem.Feed,
                FooterItem.Articles,
                FooterItem.Journal,
                FooterItem.Channels,
                FooterItem.Status,
                FooterItem.Emojis,
                FooterItem.Chat,
            ),
            movedUp.order,
        )
        assertEquals(FooterConfig.Default, movedUp.move(FooterItem.Articles, 1))
        assertEquals(FooterConfig.Default, FooterConfig.Default.move(FooterItem.Feed, -1))
        assertEquals(FooterConfig.Default, FooterConfig.Default.move(FooterItem.Chat, 1))
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
                FooterItem.Feed,
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

    @Test
    fun feedKeepsTheStoredIdOfFormerHome() {
        // 旧名「ホーム」の保存値をそのまま読めるようにする。
        assertEquals(FooterItem.Feed, FooterItem.fromStorageId("home"))
        assertEquals("フィード", FooterItem.Feed.label)
        assertFalse(FooterItem.Feed.canHide)
    }

    @Test
    fun launchItemIsTheLastOpenedTab() {
        assertEquals(FooterItem.Channels, FooterConfig.Default.launchItem(FooterItem.Channels))
        assertEquals(FooterItem.Journal, FooterConfig.Default.launchItem(FooterItem.Journal))
        // フッターの並び順には左右されない。
        assertEquals(FooterItem.Feed, FooterConfig.parse("chat,journal,home").launchItem(FooterItem.Feed))
    }

    @Test
    fun launchItemFallsBackToFeed() {
        // 一度も記録していないとき。
        assertEquals(FooterItem.Feed, FooterConfig.parse("chat,journal,home").launchItem(null))
        // 最後に開いたタブを、その後フッターから隠したとき。
        assertEquals(FooterItem.Feed, FooterConfig.parse("home,journal,!articles").launchItem(FooterItem.Articles))
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
