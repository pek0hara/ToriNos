package com.nostr.torinos.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

@Composable
actual fun CustomEmojiSettingsBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {
    BackHandler(enabled = enabled, onBack = onBack)
}
