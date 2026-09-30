package com.nostr.torinos

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.ExperimentalBrowserHistoryApi
import androidx.navigation.NavHostController
import androidx.navigation.bindToBrowserNavigation

@OptIn(ExperimentalBrowserHistoryApi::class)
@Composable
internal actual fun BindBrowserNavigation(navController: NavHostController) {
    // NavHost がグラフを設定した後、画面履歴をブラウザの戻る／進むに同期する。
    LaunchedEffect(navController) {
        navController.bindToBrowserNavigation()
    }
}
