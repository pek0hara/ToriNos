package com.nostr.torinos.ui.components

import com.nostr.torinos.model.*


import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.ui.settings.setPlainText
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

internal val prettyEventJson = Json {
    prettyPrint = true
}

/** 投稿詳細で一覧表示する、本文とタグに含まれるWeb URL。本文、タグの順に重複なく並べる。 */
internal fun extractEventUrls(event: NostrEvent): List<String> =
    (sequenceOf(event.content) + event.tags.asSequence().flatten())
        .flatMap { extractWebUrls(it) }
        .distinct()
        .toList()

/** イベントの種類・日時・URLと、生のJSONを見せる全画面ダイアログ。投稿と記事の「データを表示」で使う。 */
@Composable
internal fun EventDataDialog(
    event: NostrEvent,
    title: String,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    val eventJson = remember(event) {
        prettyEventJson.encodeToString(NostrEvent.serializer(), event)
    }
    val eventUrls = remember(event) { extractEventUrls(event) }
    val uriHandler = LocalUriHandler.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                AppTopBar(
                    title = title,
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "戻る",
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                coroutineScope.launch {
                                    clipboard.setPlainText(eventJson)
                                }
                            },
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = "JSONをコピー",
                            )
                        }
                    },
                )
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        NoteDetailRow(label = "Kind", value = event.kind.toString())
                        NoteDetailRow(label = "投稿日時", value = formatTimestamp(event.createdAt))
                        if (eventUrls.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = "URL",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                eventUrls.forEach { url ->
                                    Text(
                                        text = url,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        textDecoration = TextDecoration.Underline,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { uriHandler.openUri(url) }
                                            .padding(vertical = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        text = "イベントJSON",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    SelectionContainer {
                        Text(
                            text = eventJson,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(8.dp),
                                )
                                .padding(12.dp),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun NoteDetailRow(
    label: String,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

