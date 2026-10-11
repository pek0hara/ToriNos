package com.nostr.torinos.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/** 設定画面で選べるアプリ内の文字サイズ。OSの文字サイズにさらに掛ける倍率。 */
enum class AppFontScale(val storageValue: String, val label: String, val factor: Float) {
    Small("small", "極小", 0.85f),
    Default("default", "小", 1f),
    Large("large", "標準", 1.15f),
    ExtraLarge("extra_large", "大", 1.3f),
    ;

    companion object {
        fun fromStorage(value: String?): AppFontScale =
            entries.firstOrNull { it.storageValue == value } ?: Large
    }
}

/**
 * OSの文字サイズとアプリ設定を掛け合わせた倍率の上限。固定高さの行やバーはこの倍率まで崩れないことを前提にしている。
 * OS側だけでこれを超えている場合はOSの設定を尊重し、アプリ設定での拡大だけを止める。
 */
internal const val MaxCombinedFontScale = 1.6f

/** アプリ設定の倍率を、OSの文字サイズと掛けても [MaxCombinedFontScale] を超えないように丸める。縮小はそのまま効かせる。 */
internal fun effectiveAppFontScale(factor: Float, systemFontScale: Float): Float {
    if (factor <= 1f) return factor
    val limit = (MaxCombinedFontScale / systemFontScale).coerceAtLeast(1f)
    return factor.coerceAtMost(limit)
}

/**
 * 画面に効いているアプリ設定の倍率。Typography には反映済みなので、直書きの sp を使う箇所だけが参照する。
 * LocalDensity と違って、別ウィンドウになるダイアログやボトムシートにもそのまま引き継がれる。
 */
val LocalAppFontScale = staticCompositionLocalOf { 1f }

/** アプリの文字サイズ設定に追従する sp。文字として読ませるラベルの直書きサイズに使う。絵文字などアイコン扱いのものには使わない。 */
val Int.appSp: TextUnit
    @Composable
    @ReadOnlyComposable
    get() = (this * LocalAppFontScale.current).sp

internal fun TextStyle.scaledBy(factor: Float): TextStyle = copy(
    fontSize = fontSize.scaledSp(factor),
    lineHeight = lineHeight.scaledSp(factor),
    letterSpacing = letterSpacing.scaledSp(factor),
)

internal fun Typography.scaledBy(factor: Float): Typography {
    if (factor == 1f) return this
    return copy(
        displayLarge = displayLarge.scaledBy(factor),
        displayMedium = displayMedium.scaledBy(factor),
        displaySmall = displaySmall.scaledBy(factor),
        headlineLarge = headlineLarge.scaledBy(factor),
        headlineMedium = headlineMedium.scaledBy(factor),
        headlineSmall = headlineSmall.scaledBy(factor),
        titleLarge = titleLarge.scaledBy(factor),
        titleMedium = titleMedium.scaledBy(factor),
        titleSmall = titleSmall.scaledBy(factor),
        bodyLarge = bodyLarge.scaledBy(factor),
        bodyMedium = bodyMedium.scaledBy(factor),
        bodySmall = bodySmall.scaledBy(factor),
        labelLarge = labelLarge.scaledBy(factor),
        labelMedium = labelMedium.scaledBy(factor),
        labelSmall = labelSmall.scaledBy(factor),
    )
}

// em は文字サイズに対する比率なので、文字サイズを変えれば自動で追従する。
private fun TextUnit.scaledSp(factor: Float): TextUnit = if (isSp) this * factor else this
