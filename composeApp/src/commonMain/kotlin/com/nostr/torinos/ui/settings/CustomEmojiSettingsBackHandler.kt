package com.nostr.torinos.ui.settings

import androidx.compose.runtime.Composable

@Composable
expect fun CustomEmojiSettingsBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
)
