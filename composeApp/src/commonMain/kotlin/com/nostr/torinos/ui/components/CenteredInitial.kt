package com.nostr.torinos.ui.components

import androidx.compose.foundation.layout.offset
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * 丸いアイコンの中の頭文字。Noto Sans JP は行の箱（上 1.16em・下 0.288em）の中心が
 * ベースラインの 0.436em 上にあるが、大文字の字面の中心は 0.367em 上（キャップハイト 0.733em の半分）。
 * そのまま中央寄せすると字が 0.07em ほど下にずれて見えるので、その分だけ持ち上げる。
 */
@Composable
internal fun CenteredInitial(
    text: String,
    color: Color,
    style: TextStyle = LocalTextStyle.current,
    fontWeight: FontWeight? = FontWeight.Bold,
    modifier: Modifier = Modifier,
) {
    val lift = with(LocalDensity.current) { style.fontSize.toDp() } * INITIAL_LIFT_EM
    Text(text, color = color, style = style, fontWeight = fontWeight, modifier = modifier.offset(y = -lift))
}

private const val INITIAL_LIFT_EM = 0.07f
