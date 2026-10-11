package com.nostr.torinos.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import torinos.composeapp.generated.resources.NotoSansJP_Regular
import torinos.composeapp.generated.resources.Res
import androidx.compose.ui.graphics.Color
import org.jetbrains.compose.resources.Font

private val BrandBlue = Color(0xFF2292FF)
private val BrandBlueDark = Color(0xFF005FCC)
private val BrandBlueLight = Color(0xFF80BFFF)

private val LightColors = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6EAFF),
    onPrimaryContainer = Color(0xFF003A80),
    secondary = BrandBlueDark,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCE2FF),
    onSecondaryContainer = Color(0xFF003366),
    background = Color.White,
    onBackground = Color(0xFF1D2024),
    surface = Color.White,
    onSurface = Color(0xFF1D2024),
    surfaceVariant = Color(0xFFF2F4F7),
    onSurfaceVariant = Color(0xFF5F6670),
    outline = Color(0xFF8D949E),
    outlineVariant = Color(0xFFD7DCE2),
)

private val DarkColors = darkColorScheme(
    primary = BrandBlueLight,
    onPrimary = Color(0xFF003A80),
    primaryContainer = Color(0xFF005FCC),
    onPrimaryContainer = Color(0xFFD6EAFF),
    secondary = BrandBlueLight,
    onSecondary = Color(0xFF003366),
    secondaryContainer = Color(0xFF004499),
    onSecondaryContainer = Color(0xFFCCE2FF),
)

@Composable
fun NostrTheme(
    fontScale: AppFontScale = AppFontScale.Large,
    content: @Composable () -> Unit,
) {
    val notoSansJP = FontFamily(
        Font(Res.font.NotoSansJP_Regular, weight = FontWeight.Normal),
    )
    val baseTypography = Typography().run {
        copy(
            displayLarge = displayLarge.copy(fontFamily = notoSansJP),
            displayMedium = displayMedium.copy(fontFamily = notoSansJP),
            displaySmall = displaySmall.copy(fontFamily = notoSansJP),
            headlineLarge = headlineLarge.copy(fontFamily = notoSansJP),
            headlineMedium = headlineMedium.copy(fontFamily = notoSansJP),
            headlineSmall = headlineSmall.copy(fontFamily = notoSansJP),
            titleLarge = titleLarge.copy(fontFamily = notoSansJP),
            titleMedium = titleMedium.copy(fontFamily = notoSansJP),
            titleSmall = titleSmall.copy(fontFamily = notoSansJP),
            bodyLarge = bodyLarge.copy(fontFamily = notoSansJP),
            bodyMedium = bodyMedium.copy(fontFamily = notoSansJP),
            bodySmall = bodySmall.copy(fontFamily = notoSansJP),
            labelLarge = labelLarge.copy(fontFamily = notoSansJP),
            labelMedium = labelMedium.copy(fontFamily = notoSansJP),
            labelSmall = labelSmall.copy(fontFamily = notoSansJP),
        )
    }
    // OSの文字サイズは LocalDensity の fontScale として sp に掛かるので、アプリ設定は Typography 側で掛ける。
    // LocalDensity を差し替えると、Android で別ウィンドウになるダイアログやシートに伝わらないため。
    val appFontScale = effectiveAppFontScale(fontScale.factor, LocalDensity.current.fontScale)
    val typography = remember(baseTypography, appFontScale) { baseTypography.scaledBy(appFontScale) }
    CompositionLocalProvider(LocalAppFontScale provides appFontScale) {
        MaterialTheme(
            colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
            typography = typography,
            content = content,
        )
    }
}
