package com.nostr.torinos.ui.chat

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 表示中の行の位置（[androidx.compose.foundation.lazy.LazyListItemInfo] の必要な分だけ）。 */
internal data class VisibleRow(val key: Any, val index: Int, val offset: Int, val size: Int)

/** 表示域の下端にある行と、その行の下端の位置 [end]、そこから表示域の下端までの距離 [gap]。 */
internal data class BottomAnchor(val key: Any, val index: Int, val end: Int, val gap: Int)

internal fun bottomAnchorOf(rows: List<VisibleRow>, viewportEnd: Int): BottomAnchor? =
    rows.lastOrNull()?.let {
        val end = it.offset + it.size
        BottomAnchor(it.key, it.index, end, viewportEnd - end)
    }

/**
 * [anchor] の行を元の位置（下端からの距離）に戻すために進める量。
 * 行が表示域の外に出ていたら（縮んだとき）、上から並べる一覧では行は動かないので、覚えておいた位置を使う。
 */
internal fun bottomAnchorDelta(anchor: BottomAnchor, rows: List<VisibleRow>, viewportEnd: Int): Int {
    val end = rows.firstOrNull { it.key == anchor.key }?.let { it.offset + it.size } ?: anchor.end
    return anchor.gap - (viewportEnd - end)
}

private fun LazyListState.visibleRows(): List<VisibleRow> =
    layoutInfo.visibleItemsInfo.map { VisibleRow(it.key, it.index, it.offset, it.size) }

/**
 * 一覧の表示域の高さが変わったら（キーボードの出し入れ、入力欄の余白の付け外し）、
 * 変わる前に下端にあった行を同じ位置に戻す。上から並べる一覧は、そのままだとキーボードを出したときに
 * 下の方（最新の返信）が隠れる。
 *
 * 高さの差分だけスクロールすると、一番下にいるときに表示域が広がった場合に LazyList が自分で詰め直した分と
 * 二重になり、戻りすぎる（iOS ではフォーカスでまず入力欄の余白が外れて一覧が広がり、続いてキーボードで縮む）。
 * そのため差分ではなく、下端の行の実際の位置から戻す量を決める。
 */
@Composable
internal fun KeepBottomOnResize(listState: LazyListState) {
    LaunchedEffect(listState) {
        var previousHeight = 0
        var anchor: BottomAnchor? = null
        snapshotFlow { listState.layoutInfo }
            .collect { info ->
                val height = info.viewportSize.height
                val saved = anchor
                if (previousHeight > 0 && height != previousHeight && saved != null) {
                    val delta = bottomAnchorDelta(saved, listState.visibleRows(), info.viewportEndOffset)
                    if (delta != 0) listState.scrollUnlessPreempted { scrollBy(delta.toFloat()) }
                }
                previousHeight = height
                anchor = bottomAnchorOf(listState.visibleRows(), listState.layoutInfo.viewportEndOffset)
            }
    }
}

/**
 * スクロールを試み、ほかのスクロール（指、ほかの追従処理）に断られたり打ち切られたりしても、そのまま戻る。
 *
 * [ScrollableState.scroll] は、優先度の高いスクロールの最中なら CancellationException で断り、
 * 後から来たスクロールは先のスクロールを CancellationException で打ち切る。これを捕まえないと、
 * 高さの変化を見張る collect ごと終わり、それ以降キーボードを出しても一覧が追従しなくなる。
 * 呼んだ側そのものが取り消されたときは、そのまま投げ直す。
 */
internal suspend fun <S : ScrollableState> S.scrollUnlessPreempted(block: suspend S.() -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
    }
}
