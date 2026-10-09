package com.nostr.torinos.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AppFontScaleTest {
    @Test
    fun restoresEveryLevelFromItsStoredValue() {
        AppFontScale.entries.forEach { scale ->
            assertEquals(scale, AppFontScale.fromStorage(scale.storageValue))
        }
    }

    @Test
    fun fallsBackToDefaultForMissingOrUnknownStoredValue() {
        assertEquals(AppFontScale.Default, AppFontScale.fromStorage(null))
        assertEquals(AppFontScale.Default, AppFontScale.fromStorage(""))
        assertEquals(AppFontScale.Default, AppFontScale.fromStorage("1.3"))
    }

    @Test
    fun labelsReadFromExtraSmallToLarge() {
        assertEquals(listOf("極小", "小", "標準", "大"), AppFontScale.entries.map { it.label })
    }

    @Test
    fun levelsGrowFromSmallToExtraLargeAroundDefault() {
        val factors = AppFontScale.entries.map { it.factor }
        assertEquals(factors.sorted(), factors)
        assertEquals(1f, AppFontScale.Default.factor)
        assertEquals(AppFontScale.entries.size, AppFontScale.entries.map { it.storageValue }.toSet().size)
    }

    @Test
    fun keepsAppFactorWhenSystemFontIsStandard() {
        AppFontScale.entries.forEach { scale ->
            assertEquals(scale.factor, effectiveAppFontScale(scale.factor, systemFontScale = 1f))
        }
    }

    @Test
    fun limitsEnlargementSoCombinedScaleStaysWithinMaximum() {
        val effective = effectiveAppFontScale(AppFontScale.ExtraLarge.factor, systemFontScale = 1.5f)

        assertClose(MaxCombinedFontScale, effective * 1.5f)
    }

    @Test
    fun neverShrinksBelowSystemFontWhenEnlarging() {
        // OS側ですでに上限を超えているなら、拡大を打ち消すだけで縮小はしない。
        assertEquals(1f, effectiveAppFontScale(AppFontScale.Large.factor, systemFontScale = 2f))
    }

    @Test
    fun appliesShrinkRegardlessOfSystemFont() {
        assertEquals(AppFontScale.Small.factor, effectiveAppFontScale(AppFontScale.Small.factor, systemFontScale = 2f))
    }

    @Test
    fun scalesSpMetricsOfTextStyle() {
        val style = TextStyle(fontSize = 10.sp, lineHeight = 20.sp, letterSpacing = 0.5.sp)

        val scaled = style.scaledBy(1.5f)

        assertSpClose(15f, scaled.fontSize)
        assertSpClose(30f, scaled.lineHeight)
        assertSpClose(0.75f, scaled.letterSpacing)
    }

    @Test
    fun leavesUnspecifiedAndEmMetricsUntouched() {
        val style = TextStyle(fontSize = 10.sp, lineHeight = 1.4.em)

        val scaled = style.scaledBy(1.5f)

        assertSpClose(15f, scaled.fontSize)
        assertEquals(1.4.em, scaled.lineHeight)
        assertEquals(TextUnit.Unspecified, scaled.letterSpacing)
    }

    @Test
    fun returnsSameTypographyAtStandardScale() {
        val typography = Typography()

        assertSame(typography, typography.scaledBy(1f))
    }

    @Test
    fun scalesEveryTypographyStyle() {
        val base = Typography()
        val scaled = base.scaledBy(1.3f)

        base.styles().zip(scaled.styles()).forEach { (original, result) ->
            assertSpClose(original.fontSize.value * 1.3f, result.fontSize)
            assertSpClose(original.lineHeight.value * 1.3f, result.lineHeight)
        }
    }

    private fun Typography.styles() = listOf(
        displayLarge, displayMedium, displaySmall,
        headlineLarge, headlineMedium, headlineSmall,
        titleLarge, titleMedium, titleSmall,
        bodyLarge, bodyMedium, bodySmall,
        labelLarge, labelMedium, labelSmall,
    )

    private fun assertSpClose(expected: Float, actual: TextUnit) {
        assertTrue(actual.isSp, "expected sp but was $actual")
        assertClose(expected, actual.value)
    }

    private fun assertClose(expected: Float, actual: Float) {
        assertTrue(kotlin.math.abs(expected - actual) < 0.001f, "expected $expected but was $actual")
    }
}
