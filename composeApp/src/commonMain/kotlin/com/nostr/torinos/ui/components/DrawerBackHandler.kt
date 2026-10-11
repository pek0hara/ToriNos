package com.nostr.torinos.ui.components

import androidx.compose.runtime.Composable

/**
 * ドロワーを開いている間のシステムBack。Androidのみ有効で、iOSとWebでは何もしない。
 * 開いたときに合成して後から登録し、裏の画面のBackより優先させる。
 */
@Composable
expect fun DrawerBackHandler(onBack: () -> Unit)
