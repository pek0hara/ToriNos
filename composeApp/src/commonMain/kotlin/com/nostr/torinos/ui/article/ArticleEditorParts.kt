package com.nostr.torinos.ui.article

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nostr.torinos.article.ArticleDraftMode
import com.nostr.torinos.article.MatometrReference
import com.nostr.torinos.ui.components.avoidStaleAccessibilityFocus

/** Formatting bar joined to the top of the body field, so it reads as part of a text editor. */
@Composable
internal fun ArticleMarkdownToolbar(
    enabled: Boolean,
    isUploadingImage: Boolean,
    onHeading: () -> Unit,
    onBold: () -> Unit,
    onLink: () -> Unit,
    onImage: () -> Unit,
    onFullScreen: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraSmall.copy(
            bottomStart = androidx.compose.foundation.shape.CornerSize(0.dp),
            bottomEnd = androidx.compose.foundation.shape.CornerSize(0.dp),
        ),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        // Buttons that cannot take focus leave the caret (and the keyboard) in the body field, so typing continues.
        val keepFocus = Modifier.focusProperties { canFocus = false }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onHeading, enabled = enabled, modifier = keepFocus) { Icon(Icons.Default.Title, contentDescription = "見出し") }
            IconButton(onClick = onBold, enabled = enabled, modifier = keepFocus) { Icon(Icons.Default.FormatBold, contentDescription = "太字") }
            IconButton(onClick = onLink, enabled = enabled, modifier = keepFocus) { Icon(Icons.Default.Link, contentDescription = "リンク") }
            IconButton(onClick = onImage, enabled = enabled && !isUploadingImage, modifier = keepFocus) {
                if (isUploadingImage) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Image, contentDescription = "本文に画像を追加")
            }
            Spacer(Modifier.weight(1f))
            onFullScreen?.let { IconButton(onClick = it) { Icon(Icons.Default.OpenInFull, contentDescription = "全画面で編集") } }
        }
    }
}

@Composable
internal fun MatometrModeSelector(
    mode: ArticleDraftMode,
    enabled: Boolean,
    onCards: () -> Unit,
    onFreeMarkdown: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = mode == ArticleDraftMode.MatometrStructured,
                onClick = { if (mode != ArticleDraftMode.MatometrStructured) onCards() },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
                enabled = enabled,
            ) { Text("カードで並べる") }
            SegmentedButton(
                selected = mode == ArticleDraftMode.MatometrMarkdown,
                onClick = { if (mode != ArticleDraftMode.MatometrMarkdown) onFreeMarkdown() },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                enabled = enabled,
            ) { Text("本文に直接書く") }
        }
        Text(
            text = if (mode == ArticleDraftMode.MatometrStructured) "引用を1件ずつ並べ替え、説明を添えられます"
                else "引用を本文の好きな位置に置けます",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun MatometrReferenceCard(
    index: Int,
    count: Int,
    reference: MatometrReference,
    state: ArticleEditorState,
    enabled: Boolean,
    commentaryExpanded: Boolean,
    highlighted: Boolean,
    onToggleCommentary: () -> Unit,
    onCommentaryChange: (String) -> Unit,
    onMoveTo: (Int) -> Unit,
    onRemove: () -> Unit,
    onNoteClick: (String) -> Unit,
    onUserClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // An unclosed code block blocks publishing, so keep the field that has it open.
    val expanded = commentaryExpanded ||
        !com.nostr.torinos.article.ArticleMarkdownBoundaryValidator.isClosed(reference.commentaryMarkdown)
    var dragging by remember { mutableStateOf(false) }
    var cardHeight by remember { mutableStateOf(0) }
    var askPosition by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    // The drag callbacks outlive recompositions; read the latest position through these.
    val currentIndex by rememberUpdatedState(index)
    val currentCount by rememberUpdatedState(count)
    val currentOnMoveTo by rememberUpdatedState(onMoveTo)
    val emphasized = dragging || highlighted
    Column(
        modifier = modifier
            .fillMaxWidth()
            .zIndex(if (dragging) 1f else 0f)
            .onSizeChanged { cardHeight = it.height }
            .clip(MaterialTheme.shapes.medium)
            .background(if (emphasized) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerLow)
            .border(if (emphasized) 2.dp else 0.dp, if (emphasized) MaterialTheme.colorScheme.primary else Color.Transparent, MaterialTheme.shapes.medium)
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.DragIndicator,
                contentDescription = "ドラッグして並べ替え",
                tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier
                    .size(32.dp)
                    .pointerInput(reference.eventId, enabled) {
                        if (!enabled) return@pointerInput
                        var distance = 0f
                        detectDragGestures(
                            onDragStart = { distance = 0f; dragging = true },
                            onDragEnd = { dragging = false },
                            onDragCancel = { dragging = false },
                        ) { change, amount ->
                            change.consume()
                            distance += amount.y
                            // Step one card per half of this card's height, so the finger and the card stay roughly together.
                            val step = maxOf(cardHeight / 2f, 48.dp.toPx())
                            if (distance > step && currentIndex < currentCount - 1) {
                                currentOnMoveTo(currentIndex + 1); distance -= step * 2
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            } else if (distance < -step && currentIndex > 0) {
                                currentOnMoveTo(currentIndex - 1); distance += step * 2
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        }
                    }
                    .padding(4.dp),
            )
            Text("${index + 1}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
            Text(" / $count", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f))
            IconButton(onClick = { onMoveTo(index - 1) }, enabled = enabled && index > 0) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "引用${index + 1}を上へ移動")
            }
            IconButton(onClick = { onMoveTo(index + 1) }, enabled = enabled && index < count - 1) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "引用${index + 1}を下へ移動")
            }
            Box {
                var menuExpanded by remember { mutableStateOf(false) }
                IconButton(onClick = { menuExpanded = true }, enabled = enabled) {
                    Icon(Icons.Default.MoreVert, contentDescription = "引用${index + 1}の操作")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(text = { Text("先頭へ移動") }, enabled = index > 0,
                        leadingIcon = { Icon(Icons.Default.VerticalAlignTop, contentDescription = null) },
                        onClick = { menuExpanded = false; onMoveTo(0) })
                    DropdownMenuItem(text = { Text("末尾へ移動") }, enabled = index < count - 1,
                        leadingIcon = { Icon(Icons.Default.VerticalAlignBottom, contentDescription = null) },
                        onClick = { menuExpanded = false; onMoveTo(count - 1) })
                    DropdownMenuItem(text = { Text("位置を指定して移動…") }, enabled = count > 1,
                        leadingIcon = { Icon(Icons.Default.SwapVert, contentDescription = null) },
                        onClick = { menuExpanded = false; askPosition = true })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("削除", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menuExpanded = false; onRemove() })
                }
            }
        }
        if (askPosition) MoveToPositionDialog(current = index, count = count,
            onMove = { askPosition = false; onMoveTo(it) }, onDismiss = { askPosition = false })
        Box(Modifier.padding(end = 8.dp)) {
            ArticleQuotePreviewSlot(
                eventId = reference.eventId,
                quotedEvents = state.quotedEvents,
                quotedProfiles = state.quotedProfiles,
                loadingQuoteIds = state.loadingQuoteIds,
                onUserClick = onUserClick,
                onNoteClick = onNoteClick,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                .clickable(enabled = !expanded || commentaryExpanded, onClick = onToggleCommentary)
                .padding(vertical = 6.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null,
                modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            val firstLine = reference.commentaryMarkdown.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            Text(
                text = when {
                    expanded -> "説明"
                    firstLine == null -> "説明を追加"
                    else -> "説明: $firstLine"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (firstLine == null || expanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (expanded) {
            OutlinedTextField(
                value = reference.commentaryMarkdown,
                onValueChange = onCommentaryChange,
                placeholder = { Text("この投稿について書く（任意）") },
                enabled = enabled,
                minLines = 2,
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp).avoidStaleAccessibilityFocus(),
            )
        }
    }
}

@Composable
private fun MoveToPositionDialog(current: Int, count: Int, onMove: (Int) -> Unit, onDismiss: () -> Unit) {
    var input by remember { mutableStateOf("") }
    val target = input.toIntOrNull()?.takeIf { it in 1..count }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("引用${current + 1}を移動") },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { v -> input = v.filter(Char::isDigit).take(4) },
                label = { Text("移動先の番号（1〜$count）") },
                singleLine = true,
                isError = input.isNotEmpty() && target == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { target?.let { onMove(it - 1) } }),
                modifier = Modifier.avoidStaleAccessibilityFocus(),
            )
        },
        confirmButton = { TextButton(onClick = { target?.let { onMove(it - 1) } }, enabled = target != null && target != current + 1) { Text("移動") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}
