package com.nostr.torinos.ui.feed

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue

/**
 * フィードのクローム（トップバー・ボトムバー）の折りたたみ量。
 *
 * スクロール中は毎フレーム変わるため、値はコンポーズ中に読まず、
 * `graphicsLayer {}` や `offset {}` などのラムダの中でだけ読む。
 * こうすると変化してもレイヤー／配置だけが更新され、画面全体は再コンポーズされない。
 * 書き込みはすぐに読めるので、1フレームに複数のスクロール入力が届いても量を取りこぼさない。
 */
@Stable
class FeedChromeState {
    /** 0 = 完全表示、1 = 完全非表示。 */
    var collapseFraction by mutableFloatStateOf(0f)

    val visibility: Float
        get() = 1f - collapseFraction.coerceIn(0f, 1f)
}
