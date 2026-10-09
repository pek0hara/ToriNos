package com.nostr.torinos.ui.components

import androidx.compose.runtime.Composable

/** タブの最上位画面でのシステムBack。Androidのみ有効で、iOSとWebでは何もしない。 */
@Composable
expect fun TopLevelBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
)
