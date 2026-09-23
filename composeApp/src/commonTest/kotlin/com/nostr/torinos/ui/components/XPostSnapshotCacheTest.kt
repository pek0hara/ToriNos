package com.nostr.torinos.ui.components

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class XPostSnapshotCacheTest {
    @AfterTest
    fun tearDown() {
        XPostSnapshotCache.clear()
    }

    @Test
    fun replacingSameKeyDoesNotDoubleCountEstimatedBytes() {
        val repeated = XPostSnapshotCacheKey(postId = "repeated", darkTheme = false, widthPx = 720)
        // 1400x1400x4byte ≈ 7.48MiB(単一画像上限8MiB未満)。二重加算されると4回の置換で
        // 約29.9MiBの推定使用量になり、24MiBの総量上限を超えて自分自身まで退避されてしまう。
        // 正しく置換前の分を差し引いていれば常に約7.48MiBのままで、置換後も自分自身と
        // 後発のotherの両方が生存する。
        repeat(4) {
            XPostSnapshotCache[repeated] = ImageBitmap(1_400, 1_400)
        }
        val other = XPostSnapshotCacheKey(postId = "other", darkTheme = false, widthPx = 720)
        XPostSnapshotCache[other] = ImageBitmap(100, 100)

        assertNotNull(XPostSnapshotCache[repeated])
        assertNotNull(XPostSnapshotCache[other])
    }

    @Test
    fun singleImageOverEightMiBIsNotCached() {
        val key = XPostSnapshotCacheKey(postId = "huge", darkTheme = false, widthPx = 2_000)
        // 1500x1500x4byte ≈ 8.58MiB > 8MiBの単一画像上限。
        XPostSnapshotCache[key] = ImageBitmap(1_500, 1_500)

        assertNull(XPostSnapshotCache[key])
    }

    @Test
    fun totalBudgetEvictsOldestEntriesFirst() {
        // 各1400x1400x4byte ≈ 7.48MiB(単一画像上限8MiB未満)。3枚までは24MiBに収まるが、
        // 4枚目で総量が上限を超え、最も古いキーがLRU順に退避される。
        val keys = (1..4).map { XPostSnapshotCacheKey(postId = "$it", darkTheme = false, widthPx = 720) }
        keys.forEach { key -> XPostSnapshotCache[key] = ImageBitmap(1_400, 1_400) }

        assertNull(XPostSnapshotCache[keys[0]])
        assertNotNull(XPostSnapshotCache[keys[1]])
        assertNotNull(XPostSnapshotCache[keys[2]])
        assertNotNull(XPostSnapshotCache[keys[3]])
    }

    @Test
    fun differentThemeOrWidthAreDistinctEntries() {
        val light = XPostSnapshotCacheKey(postId = "1", darkTheme = false, widthPx = 720)
        val dark = light.copy(darkTheme = true)
        val wider = light.copy(widthPx = 1_080)
        XPostSnapshotCache[light] = ImageBitmap(100, 100)
        XPostSnapshotCache[dark] = ImageBitmap(100, 100)
        XPostSnapshotCache[wider] = ImageBitmap(100, 100)

        assertNotNull(XPostSnapshotCache[light])
        assertNotNull(XPostSnapshotCache[dark])
        assertNotNull(XPostSnapshotCache[wider])
    }

    @Test
    fun clearRemovesAllEntries() {
        val key = XPostSnapshotCacheKey(postId = "1", darkTheme = false, widthPx = 720)
        XPostSnapshotCache[key] = ImageBitmap(100, 100)

        XPostSnapshotCache.clear()

        assertNull(XPostSnapshotCache[key])
    }
}
