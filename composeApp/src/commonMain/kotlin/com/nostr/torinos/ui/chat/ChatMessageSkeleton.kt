package com.nostr.torinos.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nostr.torinos.ui.components.rememberSkeletonPulse

/** 本文の行の幅（割合）。発言ごとに長さを変えて、チャットらしく見せる。 */
private val ChatSkeletonLines = listOf(
    listOf(0.85f, 0.5f),
    listOf(0.6f),
    listOf(0.95f, 0.8f, 0.4f),
    listOf(0.45f),
    listOf(0.75f, 0.55f),
    listOf(0.7f),
)

/**
 * 発言を読み込んでいる間に出す、発言の形をした表示。寸法は MessageRow に合わせる。
 * チャットは下から積むので、下に寄せて並べる。
 */
@Composable
internal fun ChatMessageSkeleton(modifier: Modifier = Modifier, alignBottom: Boolean = true) {
    val pulse = rememberSkeletonPulse()
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val align = if (alignBottom) Alignment.BottomCenter else Alignment.TopCenter
    // 画面が低いときは、寄せた側を残して反対側を切る（行が潰れないよう高さは制限しない）。
    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .clearAndSetSemantics { contentDescription = "メッセージを読み込み中" },
        contentAlignment = align,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(align = if (alignBottom) Alignment.Bottom else Alignment.Top, unbounded = true)
                // 透明度は描画の段階でだけ読み、毎フレームの再コンポーズを起こさない。
                .graphicsLayer { alpha = pulse.value }
                .padding(vertical = 8.dp),
        ) {
            ChatSkeletonLines.forEach { lines ->
                Row(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 2.dp)) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(color))
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        SkeletonBar(color, height = 12.dp, modifier = Modifier.padding(top = 3.dp).fillMaxWidth(0.3f))
                        lines.forEach { fraction ->
                            SkeletonBar(color, height = 12.dp, modifier = Modifier.padding(top = 8.dp).fillMaxWidth(fraction))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SkeletonBar(color: Color, height: Dp, modifier: Modifier) {
    Box(modifier.height(height).clip(RoundedCornerShape(4.dp)).background(color))
}
