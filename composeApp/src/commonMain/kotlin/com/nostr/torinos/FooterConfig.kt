package com.nostr.torinos

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nostr.torinos.ui.service.ServiceTab

/**
 * フッター（横長画面ではナビゲーションレール）か、その右端の「メニュー」に並べる機能。
 * [storageId] は保存値に使うので変えない。
 */
internal enum class FooterItem(
    val storageId: String,
    val label: String,
    val icon: ImageVector,
    val serviceTab: ServiceTab? = null,
) {
    /** 保存値の互換のため [storageId] は旧名の home のまま。 */
    Feed("home", "フィード", Icons.Default.Public),
    Journal("journal", "ジャーナル", Icons.Default.Today),
    Articles("articles", ServiceTab.Articles.label, ServiceTab.Articles.icon, ServiceTab.Articles),
    Channels("channels", ServiceTab.Channels.label, ServiceTab.Channels.icon, ServiceTab.Channels),
    Status("status", ServiceTab.Status.label, ServiceTab.Status.icon, ServiceTab.Status),
    Emojis("emojis", ServiceTab.Emojis.label, ServiceTab.Emojis.icon, ServiceTab.Emojis),
    /** 既定でフッターに出す。招待リンクを持ってきた人が、参加する場所(チャットタブの参加シート)を見つけられるようにする。 */
    Chat("chat", ServiceTab.Chat.label, ServiceTab.Chat.icon, ServiceTab.Chat),
    /** タブではなく、設定画面を重ねて開く。 */
    Settings("settings", "設定", Icons.Default.Settings),
    ;

    /**
     * フィードはメニューへ移せない。URLから開いた画面や、最上位の Back の戻り先になるため。
     * 起動時の画面はフッターの並び順ではなく、前回最後に開いていたタブ（[FooterConfig.launchItem]）。
     */
    val canMoveToMenu: Boolean get() = this != Feed

    /** この項目を選んだときのナビゲーションのルート。サービスのタブは "services" の中で切り替える。 */
    val route: String
        get() = when (this) {
            Feed -> "feed"
            Journal -> "journal"
            Settings -> "settings"
            else -> "services"
        }

    companion object {
        fun fromStorageId(id: String): FooterItem? = entries.firstOrNull { it.storageId == id }
    }
}

/**
 * フッターと「メニュー」に並べる項目とその順。端末単位の表示設定として保存する。
 * 項目はどちらか一方に必ず入り、隠すことはない。[order] の順で、[menu] に入るものはメニューに、残りはフッターに並ぶ。
 */
internal data class FooterConfig(
    val order: List<FooterItem>,
    val menu: Set<FooterItem>,
) {
    /** フッターに並べる項目。「メニュー」はその右端に置く。 */
    val barItems: List<FooterItem> get() = order.filterNot { it in menu }

    /** 「メニュー」に並べる項目。 */
    val menuItems: List<FooterItem> get() = order.filter { it in menu }

    /** 起動時に開く項目。前回最後に開いていたタブ [lastOpened] を開き直す。記録が無いときはフィード。設定はタブではないので開かない。 */
    fun launchItem(lastOpened: FooterItem?): FooterItem =
        lastOpened?.takeIf { it != FooterItem.Settings } ?: FooterItem.Feed

    /** サービス画面で切り替えられるタブ。フッターとメニューのどちらからも開けるので全部。 */
    val serviceTabs: List<ServiceTab> get() = order.mapNotNull { it.serviceTab }

    fun isInMenu(item: FooterItem): Boolean = item in menu

    /** [item] をフッターとメニューの間で移す。移した先の末尾に並べる。フィードは移さない。 */
    fun togglePlacement(item: FooterItem): FooterConfig = when {
        item in menu -> of(order - item + item, menu - item)
        item.canMoveToMenu -> of(order - item + item, menu + item)
        else -> this
    }

    /**
     * [item] を、同じ側（フッターかメニュー）の [delta] 方向の隣と入れ替える。端を越える移動は無視する。
     * メニューの設定は末尾に固定なので動かさず、ほかの項目も設定の後ろへは行かない。
     */
    fun move(item: FooterItem, delta: Int): FooterConfig {
        val group = if (item in menu) menuItems - FooterItem.Settings else barItems
        val index = group.indexOf(item)
        val neighbor = group.getOrNull(index + delta)
        if (index < 0 || neighbor == null) return this
        val from = order.indexOf(item)
        val to = order.indexOf(neighbor)
        val moved = order.toMutableList()
        moved[from] = neighbor
        moved[to] = item
        return of(moved, menu)
    }

    fun canMove(item: FooterItem, delta: Int): Boolean = move(item, delta) != this

    fun serialize(): String =
        FormatPrefix + order.joinToString(",") { item ->
            if (item in menu) "$MenuMarker${item.storageId}" else item.storageId
        }

    companion object {
        /** 今の保存形式の印。これが無い保存値は、メニューが無かった頃の形式（"!" が非表示）として読む。 */
        private const val FormatPrefix = "v2:"
        private const val MenuMarker = "~"
        private const val LegacyHiddenMarker = "!"

        /**
         * [order] をフッターの項目、メニューの項目の順に並べ直す。同じ並びの設定が同じ値になるようにする。
         * 設定はタブではないので、メニューでは区切り線の下、末尾に置く。
         */
        private fun of(order: List<FooterItem>, menu: Set<FooterItem>): FooterConfig =
            FooterConfig(
                order.filterNot { it in menu } + order.filter { it in menu }.sortedBy { it == FooterItem.Settings },
                menu,
            )

        private val DefaultMenu =
            listOf(FooterItem.Channels, FooterItem.Articles, FooterItem.Emojis, FooterItem.Status, FooterItem.Settings)

        val Default = FooterConfig(
            order = listOf(FooterItem.Feed, FooterItem.Journal, FooterItem.Chat) + DefaultMenu,
            menu = DefaultMenu.toSet(),
        )

        /**
         * 未知のIDと重複は無視する。保存値にない項目（後から追加した機能）は、既定の順で末尾に既定の側へ足す。
         * フィードは常にフッターに置く。
         */
        fun parse(stored: String?): FooterConfig {
            val value = stored.orEmpty()
            if (!value.startsWith(FormatPrefix)) return parseLegacy(value)
            val order = mutableListOf<FooterItem>()
            val menu = mutableSetOf<FooterItem>()
            value.removePrefix(FormatPrefix).split(',').forEach { token ->
                val inMenu = token.startsWith(MenuMarker)
                val item = FooterItem.fromStorageId(token.removePrefix(MenuMarker).trim()) ?: return@forEach
                if (item in order) return@forEach
                order += item
                if (inMenu && item.canMoveToMenu) menu += item
            }
            return withMissingItems(order, menu)
        }

        /**
         * メニューが無かった頃の保存値。チャンネル・記事・絵文字・ステータスは既定どおりメニューへ、
         * それ以外で隠していた項目もメニューへ入れる（隠すことはなくなったので、メニューから開けるようにする）。
         */
        private fun parseLegacy(value: String): FooterConfig {
            val order = mutableListOf<FooterItem>()
            val menu = mutableSetOf<FooterItem>()
            value.split(',').forEach { token ->
                val hidden = token.startsWith(LegacyHiddenMarker)
                val item = FooterItem.fromStorageId(token.removePrefix(LegacyHiddenMarker).trim()) ?: return@forEach
                if (item in order || item in DefaultMenu) return@forEach
                order += item
                if (hidden && item.canMoveToMenu) menu += item
            }
            // メニューは既定の項目を先に、元は隠していた項目を後に並べる。
            val legacyHidden = order.filter { it in menu }
            order -= legacyHidden
            order += DefaultMenu + legacyHidden
            menu += DefaultMenu
            return withMissingItems(order, menu)
        }

        private fun withMissingItems(order: MutableList<FooterItem>, menu: MutableSet<FooterItem>): FooterConfig {
            Default.order.filterNot { it in order }.forEach { item ->
                order += item
                if (item in Default.menu) menu += item
            }
            return of(order, menu)
        }
    }
}

/** [current] がページにないとき（非表示にした・URLから開いた）は、表示中の最初のコンテンツに寄せる。 */
/** タブの最上位の画面が、フッターのどの項目か。タブに属さない画面（設定・詳細など）は null。 */
internal fun footerItemOf(route: String?, serviceTab: ServiceTab): FooterItem? = when (route) {
    "feed" -> FooterItem.Feed
    "journal" -> FooterItem.Journal
    "services" -> FooterItem.entries.firstOrNull { it.serviceTab == serviceTab }
    else -> null
}

internal fun resolveServiceTab(current: ServiceTab, pages: List<ServiceTab>): ServiceTab? =
    current.takeIf { it in pages } ?: pages.firstOrNull()

internal data class FooterItemLayout(val itemWidth: Dp, val scrollable: Boolean)

/** これより狭くなるときは均等に詰めず、横スクロールにする。 */
internal val FooterItemMinWidth = 72.dp

/**
 * 全項目が [FooterItemMinWidth] 以上で入るなら均等に並べる。入らないときは、右端の項目が半分見える幅にして
 * 横にスライドできることを示す。
 */
internal fun footerItemLayout(maxWidth: Dp, itemCount: Int): FooterItemLayout {
    if (itemCount <= 1) return FooterItemLayout(maxWidth, scrollable = false)
    val evenWidth = maxWidth / itemCount
    if (evenWidth >= FooterItemMinWidth) return FooterItemLayout(evenWidth, scrollable = false)
    val fullyVisible = (maxWidth / FooterItemMinWidth).toInt().coerceAtLeast(2)
    return FooterItemLayout(maxWidth / (fullyVisible - 0.5f), scrollable = true)
}
