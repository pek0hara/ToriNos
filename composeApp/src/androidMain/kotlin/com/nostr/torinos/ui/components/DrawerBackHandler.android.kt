package com.nostr.torinos.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

@Composable
actual fun DrawerBackHandler(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
}
