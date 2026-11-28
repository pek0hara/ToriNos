package com.nostr.torinos

import com.nostr.torinos.ui.theme.appSp
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * フッターの項目を並べる部分の高さ。端末下端の余白（iPhoneのホームインジケーターなど）はこの下に足す。
 * Material の NavigationBar は中身を最低80dpで並べるため、余白込みの固定高さに収まらず上が切れていた。
 */
internal val AppNavigationBarContentHeight = 56.dp

/** 端末下端の余白を含めたフッター全体の高さ。 */
@Composable
internal fun appNavigationBarHeight(): Dp =
    AppNavigationBarContentHeight +
        WindowInsets.systemBars.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()

internal class MainDestination(
    val icon: ImageVector,
    val label: String,
    val selected: Boolean,
    val onClick: () -> Unit,
)

internal const val FooterMenuLabel = "メニュー"

/**
 * フッターの項目を1段に並べ、右端に [menuEntries] をまとめた「メニュー」を置く。
 * 幅に入りきらないときは横にスライドでき、選択中の項目が見える位置まで自動で動かす。
 */
@Composable
internal fun AppBottomBarRow(
    destinations: List<MainDestination>,
    menuEntries: List<MainDestination>,
    modifier: Modifier = Modifier,
    menuTrailingEntries: List<MainDestination> = emptyList(),
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(AppNavigationBarContentHeight),
    ) {
        val hasMenu = menuEntries.isNotEmpty() || menuTrailingEntries.isNotEmpty()
        val layout = footerItemLayout(maxWidth, destinations.size + if (hasMenu) 1 else 0)
        val labelFontSize = bottomBarLabelFontSize(
            labels = destinations.map { it.label } + if (hasMenu) listOf(FooterMenuLabel) else emptyList(),
            itemWidth = layout.itemWidth - BottomBarLabelHorizontalPadding * 2,
        )
        val scrollState = rememberScrollState()
        val density = LocalDensity.current
        val selectedIndex = destinations.indexOfFirst { it.selected }
        val viewportPx = with(density) { maxWidth.roundToPx() }
        val itemWidthPx = with(density) { layout.itemWidth.toPx() }
        // 本文のスワイプや通知から選択が変わったときも、選択中の項目を隠したままにしない。
        LaunchedEffect(selectedIndex, layout.scrollable, itemWidthPx, viewportPx) {
            if (!layout.scrollable || selectedIndex < 0) return@LaunchedEffect
            val start = (itemWidthPx * selectedIndex).roundToInt()
            val end = (itemWidthPx * (selectedIndex + 1)).roundToInt()
            val target = when {
                start < scrollState.value -> start
                end > scrollState.value + viewportPx -> end - viewportPx
                else -> return@LaunchedEffect
            }
            scrollState.animateScrollTo(target)
        }
        Row(
            modifier = Modifier
                .fillMaxSize()
                .then(if (layout.scrollable) Modifier.horizontalScroll(scrollState) else Modifier)
                .selectableGroup(),
        ) {
            destinations.forEach { destination ->
                AppBottomBarItem(destination, labelFontSize, Modifier.width(layout.itemWidth))
            }
            if (hasMenu) {
                FooterMenuButton(menuEntries, Modifier.width(layout.itemWidth), menuTrailingEntries) { selected, expanded, onClick ->
                    AppBottomBarItem(
                        MainDestination(Icons.Default.Menu, FooterMenuLabel, selected, onClick),
                        labelFontSize,
                        Modifier.fillMaxHeight(),
                        pressed = expanded,
                    )
                }
            }
        }
    }
}

/**
 * 「メニュー」の項目と、押すと開く一覧。[button] は、メニュー内の画面を開いているとき selected を、一覧を開いている間 expanded を受け取る。
 * 開いていることは選択中の色では示さない（今いる画面の項目と2つ青くなり、どこにいるか分からなくなる）。
 * [trailingEntries]（設定など、タブを切り替えないもの）は区切り線の下に並べる。
 * フッターとナビゲーションレールで同じ一覧を使う。
 */
@Composable
internal fun FooterMenuButton(
    entries: List<MainDestination>,
    modifier: Modifier = Modifier,
    trailingEntries: List<MainDestination> = emptyList(),
    button: @Composable (selected: Boolean, expanded: Boolean, onClick: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        button((entries + trailingEntries).any { it.selected }, expanded) { expanded = true }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            // ボタンが画面の右端にあると一覧も端に貼り付き、影が切れて見えるので少し離す。
            offset = DpOffset(x = (-8).dp, y = (-4).dp),
            shape = RoundedCornerShape(12.dp),
            // 灰色がかった既定の色だと白い本文と見分けにくいので、本文と同じ色にして影で浮かせる。
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 8.dp,
        ) {
            val close = { expanded = false }
            entries.forEach { FooterMenuEntry(it, close) }
            if (trailingEntries.isNotEmpty()) {
                if (entries.isNotEmpty()) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                trailingEntries.forEach { FooterMenuEntry(it, close) }
            }
        }
    }
}

@Composable
private fun FooterMenuEntry(entry: MainDestination, onClose: () -> Unit) {
    val color = if (entry.selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    DropdownMenuItem(
        text = {
            Text(
                entry.label,
                color = color,
                fontWeight = if (entry.selected) FontWeight.Medium else FontWeight.Normal,
            )
        },
        leadingIcon = { Icon(entry.icon, contentDescription = null, tint = color) },
        onClick = {
            onClose()
            entry.onClick()
        },
    )
}

/**
 * ラベルの文字サイズ。文字サイズ設定どおりに大きくすると、「ジャーナル」「チャンネル」などが項目幅に入りきらない。
 * 全ラベルが1行に入る範囲でだけ大きくし、項目ごとに大きさがばらつかないようバー全体で揃える。
 * 標準の11spより小さくはしない（それでも入らなければ従来どおり省略）。「小」は設定どおりの大きさにする。
 */
@Composable
private fun bottomBarLabelFontSize(labels: List<String>, itemWidth: Dp): TextUnit {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // 選択中の太字のほうが幅を取るので、太字で測る。
    val style = LocalTextStyle.current.copy(fontWeight = FontWeight.Medium)
    val maxFontSize = BottomBarLabelFontSize.appSp.value
    return remember(labels, itemWidth, style, density, maxFontSize) {
        val widthPx = with(density) { itemWidth.toPx() }
        fitFontSize(BottomBarLabelFontSize.toFloat(), maxFontSize) { size ->
            labels.all { label ->
                textMeasurer.measure(
                    text = label,
                    style = style.copy(fontSize = size.sp),
                    maxLines = 1,
                    softWrap = false,
                    density = density,
                ).size.width <= widthPx
            }
        }.sp
    }
}

private const val BottomBarLabelFontSize = 11
/** 隣のラベルと接して1語に見えないよう、項目の間に最低12dpの隙間を残す。 */
private val BottomBarLabelHorizontalPadding = 6.dp

@Composable
private fun AppBottomBarItem(
    destination: MainDestination,
    labelFontSize: TextUnit,
    modifier: Modifier,
    /** 押して一覧を開いている間。アイコンの後ろに薄い背景を敷く（選択中の色とは分ける）。 */
    pressed: Boolean = false,
) {
    val color = if (destination.selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = modifier
            .fillMaxHeight()
            .selectable(
                selected = destination.selected,
                onClick = destination.onClick,
                role = Role.Tab,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 背景の有無で項目の高さが変わらないよう、背景の枠はいつも置く。
        Box(
            modifier = Modifier
                .size(width = 48.dp, height = 28.dp)
                .background(
                    if (pressed) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f) else Color.Transparent,
                    RoundedCornerShape(14.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                destination.icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(24.dp),
            )
        }
        Text(
            text = destination.label,
            color = color,
            fontSize = labelFontSize,
            lineHeight = 14.appSp,
            fontWeight = if (destination.selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = BottomBarLabelHorizontalPadding),
        )
    }
}

/**
 * 1行に収まる最大の文字サイズを [step] 刻みで探す。[maxFontSize] が [minFontSize] を下回るとき（文字サイズ「小」）は
 * [maxFontSize] をそのまま使う。
 */
internal fun fitFontSize(minFontSize: Float, maxFontSize: Float, step: Float = 0.5f, fits: (Float) -> Boolean): Float {
    if (maxFontSize <= minFontSize) return maxFontSize
    var size = maxFontSize
    while (size > minFontSize) {
        if (fits(size)) return size
        size -= step
    }
    return minFontSize
}

