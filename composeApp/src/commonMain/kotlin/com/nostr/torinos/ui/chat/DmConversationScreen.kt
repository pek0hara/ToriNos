package com.nostr.torinos.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nostr.torinos.account.LocalAccountSession
import com.nostr.torinos.chat.dmDisplayName
import com.nostr.torinos.dm.DmMessage
import com.nostr.torinos.dm.DmSendResult
import com.nostr.torinos.dm.DmSendStatus
import com.nostr.torinos.dm.dmConversationKey
import com.nostr.torinos.dm.peers
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.network.ProfileRepository
import com.nostr.torinos.ui.components.dismissKeyboardOnListTouch
import com.nostr.torinos.ui.components.keyboardAvoidingPadding
import com.nostr.torinos.ui.components.AppMessageComposer
import com.nostr.torinos.ui.components.AppTopBar
import com.nostr.torinos.ui.post.MAX_POST_CHARS
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * NIP-17 の会話画面（docs/dm-feature-design.md §8.3）。[conversationKey] は [dmConversationKey]。
 * 相手（と自分）の公開鍵は鍵から読むので、まだメッセージの無い会話も開ける。
 */
@Composable
fun DmConversationScreen(conversationKey: String, onBack: () -> Unit) {
    val session = LocalAccountSession.current
    val inbox = session?.dm
    val me = session?.pubkey.orEmpty()
    val all by (inbox?.messages ?: remember { MutableStateFlow(emptyList<DmMessage>()) }).collectAsState()
    val statuses by (inbox?.sendStatus ?: remember { MutableStateFlow(emptyMap<String, DmSendStatus>()) }).collectAsState()
    val messages = remember(all, conversationKey) {
        all.filter { dmConversationKey(it.peers(me)) == conversationKey }.sortedWith(compareBy<DmMessage> { it.createdAt }.thenBy { it.id })
    }
    val peers = remember(conversationKey) { conversationKey.split(",").filter { it.isNotEmpty() } }
    val profiles by remember(peers) {
        if (peers.isEmpty()) flowOf(emptyMap()) else ProfileRepository.observe(peers.toSet())
    }.collectAsState(emptyMap<String, NostrProfile>())
    // 開いている間に届いた分も既読にする。
    LaunchedEffect(messages.size) { inbox?.markRead(conversationKey) }
    LaunchedEffect(Unit) { inbox?.start() }

    // 下書きは画面を離れても残す。DM の中身なので端末には書かず、セッションの間だけ持つ。
    val drafts = session?.dmDrafts
    val draftKey = "dm:$conversationKey"
    var draft by rememberSaveable(conversationKey) { mutableStateOf(drafts?.draft(draftKey)?.text.orEmpty()) }
    LaunchedEffect(drafts, draftKey) {
        val stored = drafts?.awaitDraft(draftKey) ?: return@LaunchedEffect
        if (draft.isEmpty()) draft = stored.text
    }
    var sending by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val muted = session?.muteStore?.mutedPubkeys?.collectAsState()?.value.orEmpty()

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る") }
                },
                title = {
                    Text(
                        if (peers == listOf(me)) "自分" else dmDisplayName(peers, me) { profiles[it]?.bestName },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        },
    ) { padding ->
        // キーボードの分は Android だけ持ち上げる（iOS は画面全体が縮むので、付けると二重に持ち上がり入力欄が上端へ行く）。
        Column(Modifier.fillMaxSize().padding(padding).keyboardAvoidingPadding()) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().dismissKeyboardOnListTouch(),
                reverseLayout = true,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(messages.asReversed(), key = { it.id }) { message ->
                    DmBubble(
                        message = message,
                        mine = message.sender == me,
                        senderName = if (peers.size > 1) profiles[message.sender]?.bestName else null,
                        status = if (message.sender == me) statuses[message.id] else null,
                        onRetry = { inbox?.retry(message.id) },
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (peers.any { it in muted }) {
                NoticeText("ミュート中の相手です")
            }
            notice?.let { NoticeText(it) }
            AppMessageComposer(
                text = draft,
                onTextChange = { draft = it; notice = null; drafts?.update(draftKey, it) },
                onSend = {
                    val text = draft
                    sending = true
                    scope.launch {
                        try {
                            when (val result = inbox?.send(peers, text)) {
                                is DmSendResult.Sent, is DmSendResult.Failed -> {
                                    draft = ""
                                    drafts?.clear(draftKey)
                                }
                                is DmSendResult.NoRelayList -> notice = "この人は DM の受け取り先（kind 10050）を設定していないため、送れません"
                                is DmSendResult.Refused -> notice = "送れませんでした: ${result.message}"
                                DmSendResult.Empty, null -> Unit
                            }
                        } finally {
                            sending = false
                        }
                    }
                },
                placeholder = "メッセージ",
                enabled = inbox != null,
                isSending = sending,
                maxLength = MAX_POST_CHARS,
            )
        }
    }
}

@Composable
private fun NoticeText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun DmBubble(message: DmMessage, mine: Boolean, senderName: String?, status: DmSendStatus?, onRetry: () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            Column(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                if (!mine && senderName != null) {
                    Text(senderName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                Text(message.content, style = MaterialTheme.typography.bodyMedium)
                Text(
                    chatRelativeTime(message.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.End),
                )
            }
            when (status) {
                DmSendStatus.Sending -> Text("送信中…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                is DmSendStatus.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("送信できませんでした", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetry) { Text("再送") }
                }
                DmSendStatus.Sent, null -> Unit
            }
        }
    }
}
