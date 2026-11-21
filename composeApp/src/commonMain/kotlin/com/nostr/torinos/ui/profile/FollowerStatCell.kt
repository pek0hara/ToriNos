package com.nostr.torinos.ui.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.time.Clock

/**
 * フォロワー数の枠。数字・「フォロワー」・「↻ 3日前」の3行。
 * 3行目は数える前も高さを取っておき、数え終わったときに行の高さが変わらないようにする。
 */
@Composable
internal fun FollowerStatCell(
    display: FollowerStatDisplay,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier.padding(top = 12.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (display) {
                FollowerStatDisplay.Unknown -> CountText("—", color = muted)
                is FollowerStatDisplay.Counting -> CountText(display.liveCount.toString())
                is FollowerStatDisplay.Known -> Text(
                    text = buildAnnotatedString {
                        append(display.count.toString())
                        if (display.limited) {
                            withStyle(SpanStyle(fontSize = 12.sp, color = muted)) { append("+") }
                        }
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            val loading = display is FollowerStatDisplay.Counting ||
                (display is FollowerStatDisplay.Known && display.refreshing)
            if (loading) {
                // 数字の右に置くが幅は取らず、数字の中央ぞろえをずらさない。
                CircularProgressIndicator(
                    modifier = Modifier.besideWithoutSize(gap = 4.dp, size = 12.dp),
                    strokeWidth = 1.5.dp,
                )
            }
        }
        Text(
            text = "フォロワー",
            style = MaterialTheme.typography.labelSmall,
            color = muted,
        )
        Box(modifier = Modifier.height(AGE_ROW_HEIGHT), contentAlignment = Alignment.Center) {
            if (display is FollowerStatDisplay.Known) {
                FollowerCountAge(
                    countedAt = display.countedAt,
                    enabled = !display.refreshing,
                    onRefresh = onRefresh,
                )
            }
        }
    }
}

@Composable
private fun CountText(text: String, color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = color,
    )
}

@Composable
private fun FollowerCountAge(countedAt: Long, enabled: Boolean, onRefresh: () -> Unit) {
    val now by produceState(Clock.System.now().epochSeconds) {
        while (true) {
            delay(60_000L)
            value = Clock.System.now().epochSeconds
        }
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .height(AGE_ROW_HEIGHT)
            .clip(RoundedCornerShape(AGE_ROW_HEIGHT / 2))
            .clickable(enabled = enabled, onClickLabel = "フォロワー数を数え直す") { onRefresh() }
            .padding(horizontal = 8.dp)
            .alpha(if (enabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = muted,
        )
        Text(
            text = followerCountAgeLabel(countedAt, now),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = muted,
        )
    }
}

/** 幅も高さも 0 として並べ、実際には直前の要素の右に [gap] 空けて縦中央に描く。 */
private fun Modifier.besideWithoutSize(gap: Dp, size: Dp): Modifier = layout { measurable, _ ->
    val px = size.roundToPx()
    val placeable = measurable.measure(Constraints.fixed(px, px))
    layout(0, 0) { placeable.place(gap.roundToPx(), -px / 2) }
}

private val AGE_ROW_HEIGHT = 28.dp
