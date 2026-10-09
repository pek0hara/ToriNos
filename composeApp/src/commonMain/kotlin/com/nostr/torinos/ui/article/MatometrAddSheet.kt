package com.nostr.torinos.ui.article

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.nostr.torinos.ui.components.DismissKeyboardOnLeave
import com.nostr.torinos.ui.components.avoidStaleAccessibilityFocus

/** Adds posts to the open draft from pasted URLs or references, without leaving the editor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MatometrAddSheet(
    sheet: MatometrAddState,
    state: ArticleEditorState,
    onInput: (String) -> Unit,
    onRetry: () -> Unit,
    onAdd: () -> Unit,
    onPickFromTimeline: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        DismissKeyboardOnLeave()
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("投稿を追加", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onDismiss) { Text("閉じる") }
            }
            OutlinedTextField(
                value = sheet.input,
                onValueChange = onInput,
                modifier = Modifier.fillMaxWidth().avoidStaleAccessibilityFocus(),
                placeholder = { Text("投稿のURL・nevent・note を貼り付け") },
                minLines = 2,
                maxLines = 5,
            )
            summary(sheet)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(sheet.posts, key = { it.reference.eventId }) { post -> PastedPostRow(post, state, onRetry) }
            }
            sheet.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onPickFromTimeline) { Text("タイムラインから選ぶ") }
                Spacer(Modifier.weight(1f))
                Button(onClick = onAdd, enabled = sheet.ready.isNotEmpty()) {
                    Text(if (sheet.ready.isEmpty()) "追加" else "${sheet.ready.size}件追加")
                }
            }
        }
    }
}

private fun summary(sheet: MatometrAddState): String? {
    if (sheet.input.isBlank()) return null
    val found = sheet.posts.size
    val added = sheet.posts.count { it.status == PastedPostStatus.AlreadyAdded }
    return when {
        found == 0 && sheet.unsupported > 0 -> "記事やプロフィールは追加できません"
        found == 0 -> "投稿の参照が見つかりません"
        else -> buildString {
            append("${found}件見つかりました")
            val notes = listOfNotNull(added.takeIf { it > 0 }?.let { "${it}件は追加済み" },
                sheet.unsupported.takeIf { it > 0 }?.let { "記事やプロフィール${it}件は対象外" })
            if (notes.isNotEmpty()) append("（${notes.joinToString("、")}）")
        }
    }
}

@Composable
private fun PastedPostRow(post: PastedPost, state: ArticleEditorState, onRetry: () -> Unit) {
    val id = post.reference.eventId
    when (post.status) {
        PastedPostStatus.Ready, PastedPostStatus.AlreadyAdded -> Column(
            Modifier.alpha(if (post.status == PastedPostStatus.AlreadyAdded) 0.5f else 1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (post.status == PastedPostStatus.AlreadyAdded) {
                Text("追加済み", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            // Tapping a card here must not navigate away from the sheet.
            ArticleQuotePreviewSlot(id, state.quotedEvents, state.quotedProfiles, emptySet(), onUserClick = {}, onNoteClick = {})
        }
        PastedPostStatus.Loading -> StatusLine("読み込み中…")
        PastedPostStatus.Unsupported -> StatusLine("この種類の投稿は追加できません")
        PastedPostStatus.NotFound -> Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { StatusLine("投稿が見つかりませんでした（${id.take(8)}…）") }
                TextButton(onClick = onRetry) { Text("再試行") }
            }
        }
    }
}

@Composable
private fun StatusLine(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
