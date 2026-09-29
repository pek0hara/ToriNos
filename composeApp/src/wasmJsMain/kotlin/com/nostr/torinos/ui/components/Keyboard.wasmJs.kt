package com.nostr.torinos.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity

internal actual fun dismissPlatformKeyboard() = Unit

@Composable
internal actual fun rememberSoftwareKeyboardVisible(): Boolean =
    WindowInsets.ime.getBottom(LocalDensity.current) > 0
