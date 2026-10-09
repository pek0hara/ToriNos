package com.nostr.torinos.ui.article

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nostr.torinos.account.LocalAccountSession
import com.nostr.torinos.article.*
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.network.ProfileRepository
import com.nostr.torinos.ui.profile.AvatarCircle

internal data class MatometrActions(val quote: () -> Unit, val article: () -> Unit, val openNote: (String) -> Unit, val cancelAdd: () -> Unit = {})
internal val LocalMatometrActions = staticCompositionLocalOf<MatometrActions?> { null }
internal val LocalMatometrSelectionEnabled = staticCompositionLocalOf { false }

@Composable internal fun MatometrStartButton() {
    val session = LocalAccountSession.current ?: return
    val coordinator = session.matometrSelection
    val state by coordinator.state.collectAsState()
    IconButton(onClick = { if (state.active) coordinator.hide() else coordinator.start() }, enabled = !state.busy) {
        Icon(Icons.Default.Checklist, contentDescription = if (state.active) "まとめ選択を閉じる" else "投稿を選んでまとめる")
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable internal fun MatometrSelectionBar() {
    val session = LocalAccountSession.current ?: return
    val coordinator = session.matometrSelection
    val state by coordinator.state.collectAsState()
    val actions = LocalMatometrActions.current
    val authorPubkeys = remember(state.posts) { state.posts.mapTo(mutableSetOf()) { it.event.pubkey } }
    val profileUpdates = remember(authorPubkeys) { ProfileRepository.observe(authorPubkeys) }
    val profiles by profileUpdates.collectAsState(initial = ProfileRepository.getCached(authorPubkeys))
    var showList by remember(state.handoffId) { mutableStateOf(false) }
    var preview by remember(state.handoffId) { mutableStateOf<NostrEvent?>(null) }
    LaunchedEffect(state.active) {
        if (!state.active) { showList = false; preview = null }
    }
    if (!state.active) return
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            if (state.targetDraftId != null) {
                Text(
                    text = "記事「${state.targetTitle.ifBlank { "無題" }}」に追加する投稿を選択中",
                    modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalArrangement = Arrangement.Center) {
                // FlowRow uses weighted items' minimum intrinsic width to decide line breaks.
                // Reserve the full label width so the actions wrap before the count does.
                Box(Modifier.weight(1f).width(IntrinsicSize.Max)) {
                    TextButton(onClick = { showList = true }) { Text("${state.posts.size}件選択中") }
                }
                FlowRow(horizontalArrangement = Arrangement.End, verticalArrangement = Arrangement.Center) {
                    if (state.targetDraftId == null) {
                        TextButton(onClick = { actions?.quote?.invoke() }, enabled = !state.busy && state.posts.isNotEmpty() && actions != null) { Text("引用投稿") }
                        TextButton(onClick = { actions?.article?.invoke() }, enabled = !state.busy && state.posts.isNotEmpty() && actions != null) { Text("記事作成") }
                    } else {
                        TextButton(onClick = { actions?.article?.invoke() }, enabled = !state.busy && state.posts.isNotEmpty() && actions != null) { Text("${state.posts.size}件追加") }
                        TextButton(onClick = { actions?.cancelAdd?.invoke() ?: coordinator.cancel() }, enabled = !state.busy) { Text("キャンセル") }
                    }
                    if (state.busy) CircularProgressIndicator(Modifier.size(24.dp))
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                items(state.posts, key = { it.event.id }) { post ->
                    OutlinedCard(onClick = { preview = post.event }, modifier = Modifier.width(248.dp)) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${state.posts.indexOf(post) + 1}", Modifier.padding(end = 6.dp))
                            AvatarCircle(pubkey = post.event.pubkey, name = profiles[post.event.pubkey]?.bestName, pictureUrl = profiles[post.event.pubkey]?.picture, size = 28)
                            Text(post.event.content, modifier = Modifier.weight(1f).padding(start = 6.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = { coordinator.remove(post.event.id) }, enabled = !state.busy && state.committedId == null, contentPadding = PaddingValues(0.dp)) { Text("×") }
                        }
                    }
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (showList) ModalBottomSheet(
        onDismissRequest = { showList = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("選択した投稿（${state.posts.size}件）", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { showList = false }) { Text("閉じる") }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(vertical = 8.dp)) {
                if (state.posts.isEmpty()) item { Text("選択した投稿はありません") }
                items(state.posts, key = { it.event.id }) { post ->
                    val index = state.posts.indexOf(post)
                    val editable = !state.busy && state.committedId == null
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Row(
                            Modifier.fillMaxWidth().clickable { preview = post.event }.padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AvatarCircle(
                                pubkey = post.event.pubkey,
                                name = profiles[post.event.pubkey]?.bestName,
                                pictureUrl = profiles[post.event.pubkey]?.picture,
                            )
                            Text("${index + 1}. ${post.event.content.take(80)}", Modifier.weight(1f))
                        }
                        Row {
                            TextButton(onClick = { coordinator.move(post.event.id, -1) }, enabled = editable && index > 0) { Text("上へ") }
                            TextButton(onClick = { coordinator.move(post.event.id, 1) }, enabled = editable && index < state.posts.lastIndex) { Text("下へ") }
                            TextButton(onClick = { coordinator.remove(post.event.id) }, enabled = editable) { Text("解除") }
                        }
                    }
                    HorizontalDivider()
                }
            }
            TextButton(onClick = { coordinator.hide(); showList = false }, enabled = !state.busy,
                modifier = Modifier.align(Alignment.End).padding(bottom = 8.dp)) { Text("選択を閉じる") }
        }
    }
    preview?.let { event -> AlertDialog(
        onDismissRequest = { preview = null }, title = { Text("投稿プレビュー") }, text = { Text(event.content) },
        confirmButton = { TextButton(onClick = { preview = null; showList = false; actions?.openNote?.invoke(event.id) }) { Text("元投稿を開く") } },
        dismissButton = { TextButton(onClick = { preview = null }) { Text("閉じる") } },
    ) }
}

/** Kept below the normal card's timestamp; only the checkbox changes selection. */
@Composable internal fun MatometrSelectionCheckbox(event: NostrEvent?) {
    if (!LocalMatometrSelectionEnabled.current) return
    val session = LocalAccountSession.current ?: return
    val coordinator = session.matometrSelection
    val selection by coordinator.state.collectAsState()
    if (!selection.active) return
    if (event != null && !event.canSelectForMatometr()) {
        Text("対象外", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val index = selection.posts.indexOfFirst { it.event.id == event?.id }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Checkbox(
            checked = index >= 0,
            onCheckedChange = { event?.let { coordinator.toggle(it) } },
            enabled = event != null && !selection.busy && selection.committedId == null,
            modifier = Modifier.semantics {
                contentDescription = when {
                    event == null -> "元投稿が未取得のため選択できません"
                    index >= 0 -> "まとめの選択を解除、選択順${index + 1}"
                    else -> "まとめに追加"
                }
            },
        )
        if (index >= 0) {
            Text("${index + 1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        } else if (event == null) {
            Text("未取得", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
