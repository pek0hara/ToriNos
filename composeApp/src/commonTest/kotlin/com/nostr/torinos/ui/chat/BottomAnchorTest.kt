package com.nostr.torinos.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BottomAnchorTest {
    private fun item(key: String, index: Int, offset: Int, size: Int) = VisibleRow(key, index, offset, size)

    @Test
    fun anchorIsLastVisibleRowAndItsGapFromViewportEnd() {
        val anchor = bottomAnchorOf(listOf(item("root", 0, -500, 1800), item("reply", 1, 1300, 600)), viewportEnd = 1974)
        assertEquals(BottomAnchor(key = "reply", index = 1, end = 1900, gap = 74), anchor)
    }

    @Test
    fun noRowsHaveNoAnchor() {
        assertNull(bottomAnchorOf(emptyList(), viewportEnd = 1974))
    }

    @Test
    fun keyboardShrinkScrollsForwardByTheLostHeight() {
        val anchor = BottomAnchor(key = "reply", index = 1, end = 1974, gap = 0)
        // 表示域が1974→966に縮み、返信の下端は1974のまま（表示域の外）。
        val delta = bottomAnchorDelta(anchor, listOf(item("root", 0, -1026, 1800), item("reply", 1, 774, 1200)), viewportEnd = 966)
        assertEquals(1008, delta)
    }

    /**
     * 一番下にいるときに表示域が広がると、LazyList が自分で詰め直して返信の下端はすでに下端にある。
     * 高さの差分だけ戻すと二重に戻りすぎる（キーボードを閉じたとき、入力欄の余白が戻ったとき）。
     */
    @Test
    fun growWhileAtBottomNeedsNoScrollWhenListAlreadyReanchored() {
        val anchor = BottomAnchor(key = "reply", index = 1, end = 1974, gap = 0)
        val delta = bottomAnchorDelta(anchor, listOf(item("root", 0, 108, 1800), item("reply", 1, 1374, 600)), viewportEnd = 1974)
        assertEquals(0, delta)
    }

    /**
     * 下端の行の上端が下端すれすれだと、キーボードで縮んだときに行が表示域の外に出る。
     * 上から並べる一覧では縮んでも行は動かないので、覚えておいた位置から戻す量を決める。
     * （以前は scrollToItem でその行へ移ってから測り直していたが、直後の layoutInfo は古く、
     * 行が見つからずに「その行を一番上に出した」ところで止まっていた。長いスレッドで起きる。）
     */
    @Test
    fun anchorRowPushedOutOfViewUsesItsSavedPosition() {
        // 画像付きの返信の上端だけが下端（1974）近くに見えていた。表示域が1974→966に縮んだ。
        val anchor = BottomAnchor(key = "reply", index = 5, end = 1974 - 100 + 1200, gap = -1100)
        val delta = bottomAnchorDelta(anchor, listOf(item("prev", 4, 0, 900)), viewportEnd = 966)
        assertEquals(1008, delta)
    }
}
