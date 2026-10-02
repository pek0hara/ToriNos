package com.nostr.torinos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost

/** 画面固有の状態を持たない、アプリ共通の表示シェル。 */
@Composable
internal fun AppScaffold(
    navigationRail: (@Composable () -> Unit)?,
    contentWindowInsets: WindowInsets,
    containerColor: Color,
    snackbarHost: @Composable () -> Unit,
    floatingActionButton: @Composable () -> Unit,
    bottomBar: @Composable () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    val scaffold: @Composable () -> Unit = {
        Scaffold(
            contentWindowInsets = contentWindowInsets,
            containerColor = containerColor,
            snackbarHost = snackbarHost,
            floatingActionButton = floatingActionButton,
            bottomBar = bottomBar,
            content = content,
        )
    }
    if (!LocalIsWideLayout.current) {
        scaffold()
        return
    }
    // 横長画面: 左端にレールを置き、残りの領域の中央に読みやすい幅の本文を置く。
    Row(Modifier.fillMaxSize()) {
        if (navigationRail != null) {
            navigationRail()
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.TopCenter,
        ) {
            Row(Modifier.fillMaxHeight().widthIn(max = WideContentMaxWidth)) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        // 画面外に待機している通知ドロワーなどが余白に見えないようにする。
                        .clipToBounds()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    scaffold()
                }
            }
        }
    }
}

/** 横長画面（レール表示時）の本文の最大幅。 */
private val WideContentMaxWidth = 720.dp

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
    BindBrowserNavigation(navController)
}
