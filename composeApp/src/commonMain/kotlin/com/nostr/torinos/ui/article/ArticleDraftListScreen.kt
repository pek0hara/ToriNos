package com.nostr.torinos.ui.article

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nostr.torinos.account.LocalAccountSession
import com.nostr.torinos.article.*
import com.nostr.torinos.ui.components.AppTopBar
import com.nostr.torinos.ui.components.formatTimestamp
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

internal data class ArticleDraftSummary(
    val id: String, val title: String, val preview: String, val updatedAt: Long, val revision: Long,
    val kindLabel: String, val quoteCount: Int?, val topics: List<String>,
)

internal fun ArticleDraft.toSummary() = ArticleDraftSummary(
    id = draftId,
    title = title.trim().ifBlank { "無題の記事" },
    preview = articleDraftPreview(this),
    updatedAt = updatedAt,
    revision = revision,
    kindLabel = when {
        baseEventId != null -> "公開記事の編集"
        mode == ArticleDraftMode.Regular -> "通常記事"
        else -> "まとめ"
    },
    quoteCount = if (isMatometr) runCatching {
        parseArticleMarkdown(content, emptyList()).sections.flatMap { it.quoteIds }.distinct().size
    }.getOrDefault(references.size) else null,
    topics = parseArticleTopics(topicsInput).filterNot { it.equals("matometr", ignoreCase = true) },
)

/** 概要、なければ本文（まとめなら最初の説明）の冒頭を、Markdown記号と nostr: 参照を除いた1行にする。 */
internal fun articleDraftPreview(draft: ArticleDraft, maxLength: Int = 120): String {
    val source = draft.summary.ifBlank {
        if (draft.mode == ArticleDraftMode.MatometrStructured)
            draft.bodyMarkdown.ifBlank { draft.references.firstOrNull { it.commentaryMarkdown.isNotBlank() }?.commentaryMarkdown.orEmpty() }
        else draft.bodyMarkdown
    }
    val plain = source.lineSequence()
        .filterNot { it.trimStart().startsWith("```") }
        // Block markers only count at the start of a line; "A - B" keeps its dash.
        .map { it.replace(Regex("""^\s*(#{1,6}|>+|[-*+]|\d+\.)\s+"""), "") }
        .joinToString(" ")
        .replace(Regex("""!\[[^\]]*]\([^)]*\)"""), " ")
        .replace(Regex("""\[([^\]]*)]\([^)]*\)"""), "$1")
        .replace(Regex("""nostr:[a-z0-9]+""", RegexOption.IGNORE_CASE), " ")
        // A single underscore is often part of a word (snake_case), so only doubled ones are emphasis.
        .replace(Regex("""\*+|__+|`+|~~"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()
    return if (plain.length <= maxLength) plain else plain.take(maxLength).trimEnd() + "…"
}

/** 今日は時刻だけ、6日前までは「昨日」「N日前」、それより前は日付と時刻。 */
internal fun formatDraftUpdatedAt(
    epochSeconds: Long,
    nowEpochSeconds: Long = Clock.System.now().epochSeconds,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): String {
    val days = try {
        Instant.fromEpochSeconds(epochSeconds).toLocalDateTime(timeZone).date
            .daysUntil(Instant.fromEpochSeconds(nowEpochSeconds).toLocalDateTime(timeZone).date)
    } catch (_: Exception) { return "" }
    return when (days) {
        0 -> formatTimestamp(epochSeconds, todayTimeOnly = true, nowEpochSeconds = nowEpochSeconds, timeZone = timeZone)
        1 -> "昨日"
        in 2..6 -> "${days}日前"
        else -> formatTimestamp(epochSeconds, nowEpochSeconds = nowEpochSeconds, timeZone = timeZone)
    }
}

@Composable internal fun ArticleDraftListScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val session = LocalAccountSession.current ?: return
    val owner = session.pubkey
    val store = session.articleDraftStore
    var summaries by remember { mutableStateOf<List<ArticleDraftSummary>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var deletion by remember { mutableStateOf<ArticleDraftSummary?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var issues by remember { mutableStateOf<List<ArticleDraftLoadIssue>>(emptyList()) }
    var incompleteRelays by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()
    suspend fun reload() {
        try {
            loading = true
            store.refreshDrafts(owner)
            val data = store.read(owner)
            issues = data.relayDraftIssues
            incompleteRelays = data.incompleteDraftRelays
            summaries = data.drafts.values.sortedByDescending { it.updatedAt }.map { it.toSummary() }
            error = null
        } catch (e: Exception) { error = e.message } finally { loading = false }
    }
    LaunchedEffect(owner) { reload() }
    Scaffold(topBar = { AppTopBar(title = if (loading || error != null) "記事下書き" else "記事下書き（${summaries.size}件）", actions = { TextButton(onClick = { scope.launch { reload() } }, enabled = !loading) { Text("再取得") } }, navigationIcon = { TextButton(onClick = onBack) { Text("戻る") } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (loading) item { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            if (!loading && error == null && incompleteRelays.isNotEmpty()) item {
                Text("次のリレーから取得できなかったため、一部の下書きが表示されていないか古い可能性があります\n${incompleteRelays.joinToString("\n")}",
                    color = MaterialTheme.colorScheme.error)
            }
            items(issues, key = { "issue-${it.eventId}" }) { issue ->
                Text("${issue.message}\n下書きID: ${issue.identifier}", color = MaterialTheme.colorScheme.error)
            }
            error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error); TextButton(onClick = { scope.launch { reload() } }) { Text("再読込") } } }
            if (!loading && error == null && summaries.isEmpty()) item { Text("下書きはありません") }
            items(summaries, key = { it.id }) { d ->
                ArticleDraftCard(d, onOpen = { onOpen(d.id) }, onDelete = { deletion = d })
            }
        }
    }
    deletion?.let { d -> AlertDialog(onDismissRequest = { deletion = null }, title = { Text("下書きを削除しますか？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(d.title, style = MaterialTheme.typography.titleSmall)
                Text(d.preview.ifEmpty { "本文なし" }, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text("${d.kindLabel} · ${formatDraftUpdatedAt(d.updatedAt)}に更新", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { deleting = true; scope.launch {
            try { store.delete(owner, d.id, d.revision); deletion = null; reload() }
            catch (e: Exception) { error = e.message; deletion = null }
            finally { deleting = false }
        } }, enabled = !deleting, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("削除") } },
        dismissButton = { TextButton(onClick = { deletion = null }) { Text("キャンセル") } }) }
}

@Composable private fun ArticleDraftCard(d: ArticleDraftSummary, onOpen: () -> Unit, onDelete: () -> Unit) {
    var menuExpanded by remember { mutableStateOf(false) }
    OutlinedCard(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 10.dp, end = 4.dp)) {
            Column(Modifier.weight(1f).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(d.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(d.preview.ifEmpty { "本文なし" }, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                        Text(d.kindLabel, Modifier.padding(horizontal = 6.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                    val meta = listOfNotNull(d.quoteCount?.let { "引用${it}件" }, d.topics.take(2).joinToString(" ") { "#$it" }.ifEmpty { null })
                    Text(meta.joinToString(" · "), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(formatDraftUpdatedAt(d.updatedAt), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            Box {
                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.MoreVert, contentDescription = "下書きメニュー", modifier = Modifier.size(20.dp))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(text = { Text("開く") }, onClick = { menuExpanded = false; onOpen() })
                    DropdownMenuItem(text = { Text("削除", color = MaterialTheme.colorScheme.error) }, onClick = { menuExpanded = false; onDelete() })
                }
            }
        }
    }
}
