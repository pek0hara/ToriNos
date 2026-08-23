package com.nostr.torinos

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost

/** 画面固有の状態を持たない、アプリ共通の表示シェル。 */
@Composable
internal fun AppScaffold(
    contentWindowInsets: WindowInsets,
    containerColor: Color,
    snackbarHost: @Composable () -> Unit,
    floatingActionButton: @Composable () -> Unit,
    bottomBar: @Composable () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        contentWindowInsets = contentWindowInsets,
        containerColor = containerColor,
        snackbarHost = snackbarHost,
        floatingActionButton = floatingActionButton,
        bottomBar = bottomBar,
        content = content,
    )
}

/** ルート登録を画面シェルから分離するための NavHost 境界。 */
@Composable
internal fun AppNavigationGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    builder: NavGraphBuilder.() -> Unit,
) {
    NavHost(
        navController = navController,
        startDestination = "feed",
        modifier = modifier,
        builder = builder,
    )
}
