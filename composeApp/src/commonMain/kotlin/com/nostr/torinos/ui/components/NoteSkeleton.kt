package com.nostr.torinos.ui.components

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nostr.torinos.ui.feed.FeedViewModel.InitialFeedState
import kotlin.math.abs

/** 投稿一覧の、投稿の代わりに出すもの。[None] は投稿を出す。 */
internal enum class TimelinePlaceholder { Skeleton, Failed, Empty, None }

/** 読み込み中（遅れている場合も含む）は文言を出さず、同じスケルトンを出し続ける。 */
internal fun timelinePlaceholder(hasEvents: Boolean, initialFeedState: InitialFeedState): TimelinePlaceholder = when {
    hasEvents -> TimelinePlaceholder.None
    initialFeedState == InitialFeedState.Loading || initialFeedState == InitialFeedState.Slow ->
        TimelinePlaceholder.Skeleton
    initialFeedState == InitialFeedState.Failed -> TimelinePlaceholder.Failed
    else -> TimelinePlaceholder.Empty
}

internal const val NoteSkeletonRowCount = 6
private const val SkeletonPulsePeriodMillis = 1_800L
private const val SkeletonFadeOutMillis = 150
private const val SkeletonDescription = "投稿を読み込み中"

/** 点滅の透明度（0.5〜1.0）。フレームの時刻から決めるので、別の画面のスケルトンとも明るさがそろう。 */
internal fun skeletonPulseAlpha(frameTimeMillis: Long): Float {
    val phase = (frameTimeMillis % SkeletonPulsePeriodMillis).toFloat() / SkeletonPulsePeriodMillis
    return 0.5f + 0.5f * (1f - abs(2f * phase - 1f))
}

/** 一覧1つにつき1つだけ作り、各行に渡す。行ごとに作ると、毎フレームの更新が行の数だけ走る。 */
@Composable
internal fun rememberSkeletonPulse(): State<Float> = produceState(1f) {
    while (true) {
        withFrameMillis { value = skeletonPulseAlpha(it) }
    }
}

/**
 * 投稿カード1件ぶんの形をした読み込み中の表示。寸法は [NoteCard] に合わせ、投稿に切り替わったときに行の位置が動かないようにする。
 * [describe] が true の行だけ読み上げの対象にする（6行を1回だけ読ませる）。
 */
@Composable
internal fun NoteSkeletonRow(pulse: State<Float>, describe: Boolean, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { if (describe) contentDescription = SkeletonDescription },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 透明度は描画の段階でだけ読み、毎フレームの再コンポーズを起こさない。
                .graphicsLayer { alpha = pulse.value }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                modifier = Modifier.widthIn(min = 42.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(42.dp).clip(CircleShape).background(color))
                SkeletonBar(color, height = 10.dp, modifier = Modifier.padding(top = 9.dp).width(28.dp))
            }
            // 縦の位置は、本文2行の投稿を実機で測った値に合わせる（行の高さ 142dp）。
            Column(modifier = Modifier.weight(1f)) {
                SkeletonBar(color, height = 14.dp, modifier = Modifier.padding(top = 17.dp).fillMaxWidth(0.35f))
                SkeletonBar(color, height = 12.dp, modifier = Modifier.padding(top = 20.dp).fillMaxWidth())
                SkeletonBar(color, height = 12.dp, modifier = Modifier.padding(top = 12.dp).fillMaxWidth(0.7f))
                SkeletonBar(color, height = 14.dp, modifier = Modifier.padding(top = 16.dp).fillMaxWidth(0.4f))
                Spacer(Modifier.height(9.dp))
            }
        }
        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        )
    }
}

@Composable
private fun SkeletonBar(color: Color, height: Dp, modifier: Modifier) {
    Box(modifier.height(height).clip(RoundedCornerShape(4.dp)).background(color))
}

/** 一覧の項目としてスケルトンを並べる。投稿に替わるときはフェードで消し、投稿のフェードと重ねる。 */
internal fun LazyListScope.noteSkeletonItems(pulse: State<Float>) {
    items(
        count = NoteSkeletonRowCount,
        key = { "skeleton-$it" },
        contentType = { "skeleton" },
    ) { index ->
        NoteSkeletonRow(
            pulse = pulse,
            describe = index == 0,
            modifier = Modifier.animateItem(
                fadeInSpec = null,
                placementSpec = null,
                fadeOutSpec = tween(SkeletonFadeOutMillis),
            ),
        )
    }
}

/** 一覧の外（フォロー一覧を待つ間など）で、一覧と同じ位置にスケルトンを並べる。 */
@Composable
internal fun NoteSkeletonList(contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    val pulse = rememberSkeletonPulse()
    Column(modifier.padding(contentPadding)) {
        repeat(NoteSkeletonRowCount) { index -> NoteSkeletonRow(pulse = pulse, describe = index == 0) }
    }
}
