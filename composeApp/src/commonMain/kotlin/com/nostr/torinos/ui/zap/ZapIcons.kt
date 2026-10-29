package com.nostr.torinos.ui.zap

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** 稲妻の形（Feather Icons の `zap`、MIT）。 */
private fun PathBuilder.bolt() {
    moveTo(13f, 2f)
    lineTo(3f, 14f)
    lineTo(12f, 14f)
    lineTo(11f, 22f)
    lineTo(21f, 10f)
    lineTo(12f, 10f)
    close()
}

internal object ZapIcons {
    /** 線の⚡。色は Icon の tint で付ける。 */
    val Outlined: ImageVector by lazy {
        ImageVector.Builder("ZapOutlined", 24.dp, 24.dp, 24f, 24f).path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) { bolt() }.build()
    }

    /** 塗った⚡。自分が Zap 済みの投稿に使う。 */
    val Filled: ImageVector by lazy {
        ImageVector.Builder("ZapFilled", 24.dp, 24.dp, 24f, 24f).path(
            fill = SolidColor(Color.Black),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) { bolt() }.build()
    }
}

internal val ZapAmber = Color(0xFFF5A623)
