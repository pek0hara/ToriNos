package com.nostr.torinos.ui.components

import com.nostr.torinos.ui.theme.appSp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AppTopBar(
    title: String,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    AppTopBar(
        title = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = navigationIcon,
        actions = actions,
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AppTopBar(
    title: @Composable () -> Unit,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    val containerColor = MaterialTheme.colorScheme.background
    val contentColor = MaterialTheme.colorScheme.onBackground

    Column {
        Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
        TopAppBar(
            title = {
                CompositionLocalProvider(
                    LocalTextStyle provides MaterialTheme.typography.titleLarge.copy(fontSize = 18.appSp),
                ) {
                    title()
                }
            },
            navigationIcon = navigationIcon,
            actions = actions,
            modifier = Modifier.height(60.dp),
            windowInsets = WindowInsets(0),
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = containerColor,
                scrolledContainerColor = containerColor,
                navigationIconContentColor = contentColor,
                titleContentColor = contentColor,
                actionIconContentColor = contentColor,
            ),
        )
    }
}

/**
 * ヘッダーのタイトル位置に置く、押して切り替えメニューを開くボタン（名前と補足の2行＋▼）。
 * 押したときの波紋だけを角丸にし、文字は切り取らない。
 * 行全体を角丸で切り取ると、2行目が下端に接するため、左下の角で「p」などの下に伸びる部分が欠ける。
 */
@Composable
fun HeaderSelectorButton(
    onClickLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(modifier = modifier) {
        Box(
            Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(12.dp))
                .indication(interactionSource, ripple()),
        )
        Row(
            modifier = Modifier.clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClickLabel = onClickLabel,
                onClick = onClick,
            ),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}
