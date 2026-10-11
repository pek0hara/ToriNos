package com.nostr.torinos

import com.nostr.torinos.ui.theme.appSp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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

/**
 * フッターの項目を1段に並べる。幅に入りきらないときは横にスライドでき、選択中の項目が見える位置まで自動で動かす。
 */
@Composable
internal fun AppBottomBarRow(
    destinations: List<MainDestination>,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(AppNavigationBarContentHeight),
    ) {
        val layout = footerItemLayout(maxWidth, destinations.size)
        val labelFontSize = bottomBarLabelFontSize(
            labels = destinations.map { it.label },
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
        }
    }
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
private val BottomBarLabelHorizontalPadding = 2.dp

@Composable
private fun AppBottomBarItem(destination: MainDestination, labelFontSize: TextUnit, modifier: Modifier) {
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
        Icon(
            destination.icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.height(2.dp))
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

