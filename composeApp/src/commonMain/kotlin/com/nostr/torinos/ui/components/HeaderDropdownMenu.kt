package com.nostr.torinos.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onLongClick as semanticsOnLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** ヘッダーの切り替え操作に使う、共通の控えめなメニュー。 */
@Composable
internal fun HeaderDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = Modifier.widthIn(min = 220.dp, max = 300.dp),
        shape = RoundedCornerShape(12.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 4.dp,
        content = content,
    )
}

@Composable
internal fun HeaderDropdownMenuItem(
    label: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    leadingIcon: (@Composable () -> Unit)? = null,
    /** 2行目の補足(ホスト名など)。 */
    supportingText: String? = null,
    /** 名前の横に添える小さなラベル(「Buzz」など)。 */
    badge: String? = null,
    /** 長押しの操作(リレーを非表示にする など)。あれば長押しで呼ぶ。 */
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    DropdownMenuItem(
        text = {
            androidx.compose.foundation.layout.Column(modifier = Modifier.padding(vertical = if (supportingText != null) 6.dp else 0.dp)) {
                androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = if (supportingText != null) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (badge != null) {
                        androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
                        androidx.compose.material3.Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ) {
                            Text(badge, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
                        }
                    }
                }
                if (supportingText != null) {
                    Text(
                        supportingText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        onClick = onClick,
        modifier = Modifier
            .semantics { this.selected = selected }
            .then(
                if (onLongClick != null) {
                    // DropdownMenuItem の clickable より先に長押しを拾う(タップは素通しする)。
                    Modifier.pointerInput(onLongClick) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            // 時間内に離した・取り消したら true(タップは DropdownMenuItem に任せる)。時間切れなら null。
                            val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                waitForUpOrCancellation(pass = PointerEventPass.Initial)
                                true
                            }
                            if (ended != null) return@awaitEachGesture
                            // 指が離れる前に動いて取り消されていないか確かめてから呼ぶ。
                            val stillDown = currentEvent.changes.any { it.id == down.id && it.pressed }
                            if (!stillDown) return@awaitEachGesture
                            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            // 離すまでのイベントは消費して、タップとして選ばれないようにする。
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })
                            // 離してから呼ぶ。押したまま呼ぶと、メニューが閉じて離した操作が次に出す画面
                            // (確認のダイアログの外側)に届き、すぐ閉じてしまう。
                            onLongClick()
                        }
                    }.semantics {
                        semanticsOnLongClick(label = onLongClickLabel) { onLongClick(); true }
                    }
                } else {
                    Modifier
                },
            ),
        leadingIcon = leadingIcon,
        trailingIcon = if (selected) {
            {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        } else null,
    )
}
