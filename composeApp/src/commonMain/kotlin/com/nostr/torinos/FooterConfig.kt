package com.nostr.torinos

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Today
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nostr.torinos.ui.service.ServiceTab

/**
 * フッター（横長画面ではナビゲーションレール）に並べられる機能。
 * [storageId] は保存値に使うので変えない。[defaultVisible] が false の項目は、設定で選ぶまで表示しない。
 */
internal enum class FooterItem(
    val storageId: String,
    val label: String,
    val icon: ImageVector,
    val serviceTab: ServiceTab? = null,
    val defaultVisible: Boolean = true,
) {
    Home("home", "ホーム", Icons.Default.Home),
    Journal("journal", "ジャーナル", Icons.Default.Today),
    Articles("articles", ServiceTab.Articles.label, ServiceTab.Articles.icon, ServiceTab.Articles),
    Channels("channels", ServiceTab.Channels.label, ServiceTab.Channels.icon, ServiceTab.Channels),
    Status("status", ServiceTab.Status.label, ServiceTab.Status.icon, ServiceTab.Status, defaultVisible = false),
    Emojis("emojis", ServiceTab.Emojis.label, ServiceTab.Emojis.icon, ServiceTab.Emojis, defaultVisible = false),
    ;

    /** ホームは起動時の画面と最上位の Back の戻り先なので、隠せない。 */
    val canHide: Boolean get() = this != Home

    companion object {
        fun fromStorageId(id: String): FooterItem? = entries.firstOrNull { it.storageId == id }
    }
}

/** フッターの並び順と非表示の項目。端末単位の表示設定として保存する。 */
internal data class FooterConfig(
    val order: List<FooterItem>,
    val hidden: Set<FooterItem>,
) {
    val visibleItems: List<FooterItem> get() = order.filter { it !in hidden }

    /** サービス画面で切り替えられるタブ。フッターと同じ順に並ぶ。 */
    val serviceTabs: List<ServiceTab> get() = visibleItems.mapNotNull { it.serviceTab }

    fun isVisible(item: FooterItem): Boolean = item !in hidden

    fun toggleVisible(item: FooterItem): FooterConfig = when {
        !item.canHide -> this
        item in hidden -> copy(hidden = hidden - item)
        else -> copy(hidden = hidden + item)
    }

    /** [item] を [delta] 方向の隣と入れ替える。端を越える移動は無視する。 */
    fun move(item: FooterItem, delta: Int): FooterConfig {
        val from = order.indexOf(item)
        val to = from + delta
        if (from < 0 || to !in order.indices) return this
        val moved = order.toMutableList()
        moved[from] = moved[to]
        moved[to] = item
        return copy(order = moved)
    }

    fun serialize(): String =
        order.joinToString(",") { item -> if (item in hidden) "!${item.storageId}" else item.storageId }

    companion object {
        val Default = FooterConfig(
            order = FooterItem.entries.toList(),
            hidden = FooterItem.entries.filterNot { it.defaultVisible }.toSet(),
        )

        /**
         * 未知のIDと重複は無視する。保存値にない項目（後から追加した機能）は、既定の順で末尾に既定の表示状態で足す。
         * ホームは常に表示にする。
         */
        fun parse(stored: String?): FooterConfig {
            val order = mutableListOf<FooterItem>()
            val hidden = mutableSetOf<FooterItem>()
            stored.orEmpty().split(',').forEach { token ->
                val isHidden = token.startsWith("!")
                val item = FooterItem.fromStorageId(token.removePrefix("!").trim()) ?: return@forEach
                if (item in order) return@forEach
                order += item
                if (isHidden && item.canHide) hidden += item
            }
            FooterItem.entries.filterNot { it in order }.forEach { item ->
                order += item
                if (!item.defaultVisible) hidden += item
            }
            return FooterConfig(order, hidden)
        }
    }
}

/** [current] がページにないとき（非表示にした・URLから開いた）は、表示中の最初のコンテンツに寄せる。 */
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
