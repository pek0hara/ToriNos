package com.nostr.torinos.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import torinos.composeapp.generated.resources.NotoSansJP_Regular
import torinos.composeapp.generated.resources.Res
import androidx.compose.ui.graphics.Color
import org.jetbrains.compose.resources.Font

private val SkyBlue      = Color(0xFF2292FF) // R=34 G=146 B=255
private val SkyBlueDark  = Color(0xFF005FCC) // より暗いバリアント
private val SkyBlueLight = Color(0xFF80BFFF) // ダークテーマ用明るいバリアント

private val LightColors = lightColorScheme(
    primary = SkyBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6EAFF),
    onPrimaryContainer = Color(0xFF003A80),
    secondary = SkyBlueDark,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCE2FF),
    onSecondaryContainer = Color(0xFF003366),
)

private val DarkColors = darkColorScheme(
    primary = SkyBlueLight,
    onPrimary = Color(0xFF003A80),
    primaryContainer = Color(0xFF005FCC),
    onPrimaryContainer = Color(0xFFD6EAFF),
    secondary = SkyBlueLight,
    onSecondary = Color(0xFF003366),
    secondaryContainer = Color(0xFF004499),
    onSecondaryContainer = Color(0xFFCCE2FF),
)

@Composable
fun NostrTheme(content: @Composable () -> Unit) {
    val notoSansJP = FontFamily(
        Font(Res.font.NotoSansJP_Regular, weight = FontWeight.Normal),
    )
    val typography = Typography().run {
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
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = typography,
        content = content,
    )
}
