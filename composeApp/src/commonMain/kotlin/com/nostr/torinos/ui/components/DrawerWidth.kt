package com.nostr.torinos.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 左右から出す引き出し画面（プロフィール・通知）の共通の幅。
 * 「画面幅の [SideDrawerWidthFraction]」と「[SideDrawerMaxWidth]」の小さいほうにする。
 * fillMaxWidth の後に widthIn(max) を付けても、幅が先に固定されて上限が効かないため、ここでまとめて決める。
 */
internal fun Modifier.sideDrawerWidth(): Modifier = layout { measurable, constraints ->
    val width = minOf(
        (constraints.maxWidth * SideDrawerWidthFraction).roundToInt(),
        SideDrawerMaxWidth.roundToPx(),
    ).coerceIn(constraints.minWidth, constraints.maxWidth)
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(width, placeable.height) { placeable.place(0, 0) }
}

/** 狭い画面では本文を少し残して、押せば閉じられることを示す。 */
private const val SideDrawerWidthFraction = 0.92f

/**
 * 縦長・横長で共通の上限。スマートフォンの縦画面の幅（〜430dp）では割合のほうが効き、
 * それより広い画面では本文を覆いすぎないよう、Material 3 のモーダルサイドシートの最大幅にそろえる。
 */
private val SideDrawerMaxWidth = 400.dp
