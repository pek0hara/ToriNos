package com.example.nostr.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Purple = Color(0xFF7B61FF)
private val PurpleLight = Color(0xFFB39DDB)
private val PurpleDark = Color(0xFF4A148C)

private val LightColors = lightColorScheme(
    primary = Purple,
    onPrimary = Color.White,
    secondary = PurpleLight,
    onSecondary = Color.Black,
)

private val DarkColors = darkColorScheme(
    primary = PurpleLight,
    onPrimary = Color.Black,
    secondary = Purple,
    onSecondary = Color.White,
)

@Composable
fun NostrTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
