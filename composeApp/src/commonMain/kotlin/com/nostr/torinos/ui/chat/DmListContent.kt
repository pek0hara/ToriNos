package com.nostr.torinos.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nostr.torinos.chat.dmDisplayName
import com.nostr.torinos.dm.DmConversationSummary
import com.nostr.torinos.dm.DmInboxState
import com.nostr.torinos.dm.DmInboxStatus
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.network.ProfileRepository
import kotlinx.coroutines.flow.flowOf

/**
 * NIP-17 の会話一覧（docs/dm-feature-design.md §8.4.5）。グループが無い人の「すべて」と、
 * グループがある人の「DM」の選択で出す。
 */
@Composable
internal fun DmListContent(
    state: DmInboxState,
    ownPubkey: String?,
    onOpen: (DmConversationSummary) -> Unit,
    onJoinGroup: () -> Unit,
    /** グループが無い人の画面では、グループへの参加の案内を小さく添える。 */
    showJoinHint: Boolean,
) {
    var showRequests by rememberSaveable { mutableStateOf(false) }
    val lists = state.lists
    val people = remember(lists) { (lists.conversations + lists.requests).flatMap { it.peers }.toSet() }
    val profiles by remember(people) {
        if (people.isEmpty()) flowOf(emptyMap()) else ProfileRepository.observe(people)
    }.collectAsState(emptyMap<String, NostrProfile>())
    val status = state.status
    val loading = status is DmInboxStatus.NotStarted || status is DmInboxStatus.Loading

    if (lists.conversations.isEmpty() && lists.requests.isEmpty()) {
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            DmEmptyState(state, showJoinHint, onJoinGroup)
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
        dmNotices(state)
        if (lists.requests.isNotEmpty()) {
            item(key = "dm-requests") {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { showRequests = !showRequests }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (showRequests) "リクエストを閉じる" else "リクエスト ${lists.requests.size}",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
            }
            if (showRequests) {
                items(lists.requests, key = { "dmr-" + it.key }) { conversation ->
                    DmRow(conversation, ownPubkey, profiles, onClick = { onOpen(conversation) })
                }
                item(key = "dm-requests-end") { SectionDivider() }
            }
        }
        items(lists.conversations, key = { "dm-" + it.key }) { conversation ->
            DmRow(conversation, ownPubkey, profiles, onClick = { onOpen(conversation) })
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.dmNotices(state: DmInboxState) {
    when (val status = state.status) {
        is DmInboxStatus.Opening -> item(key = "dm-opening") { NoticeLine("DM を開封中 ${status.done} / ${status.total}") }
        is DmInboxStatus.Failed -> item(key = "dm-failed") { NoticeLine("一部の DM を開封できませんでした: ${status.message}") }
        else -> Unit
    }
    if (state.hasRelayList == false) {
        item(key = "dm-no-relays") { NoticeLine("DM の受信先（kind 10050）が未設定です。ほかのアプリから DM を送ってもらうには、受信先の設定が必要です") }
    }
}

@Composable
private fun NoticeLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SectionDivider() {
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

@Composable
private fun DmEmptyState(state: DmInboxState, showJoinHint: Boolean, onJoinGroup: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.Mail, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text("DM はまだありません", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        val detail = when {
            state.status is DmInboxStatus.Failed -> "DM を開封できませんでした: ${state.status.message}"
            state.hasRelayList == false -> "DM の受信先（kind 10050）が未設定です。ほかのアプリから DM を送ってもらうには、受信先の設定が必要です"
            else -> "届いた DM がここに表示されます"
        }
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (showJoinHint) {
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onJoinGroup) {
                Icon(Icons.Default.Groups, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("NIP-29 のグループに参加する")
            }
        }
    }
}

@Composable
private fun DmRow(
    conversation: DmConversationSummary,
    ownPubkey: String?,
    profiles: Map<String, NostrProfile>,
    onClick: () -> Unit,
) {
    val me = ownPubkey.orEmpty()
    val title = if (conversation.peers == listOf(me)) "自分" else dmDisplayName(conversation.peers, me) { profiles[it]?.bestName }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val peer = conversation.peers.first()
        if (conversation.isGroup) {
            GroupIcon(picture = null, locked = false, channel = false)
        } else {
            ChatAvatar(pubkey = peer, name = profiles[peer]?.bestName, pictureUrl = profiles[peer]?.picture, size = 40)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (conversation.unread) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    chatRelativeTime(conversation.last.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (conversation.unread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val preview = conversation.last.content.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
                Text(
                    (if (conversation.last.sender == me && conversation.peers != listOf(me)) "自分: " else "") + preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (conversation.unread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (conversation.unread) {
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                }
            }
        }
    }
}

/** NIP-17 の新しい DM の宛先を選ぶ（フォロー中の人から名前で探すか、npub を貼る）。選ぶと会話画面を開く。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun NewDmSheet(
    ownPubkey: String?,
    follows: Set<String>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val typed = remember(query) { com.nostr.torinos.ui.post.parsePubkeyInput(query.trim()) }
    // 名前で探せるよう、フォロー中の人のプロフィールを読む（多すぎるときは先頭だけ）。
    val candidates = remember(follows) { follows.filter { it != ownPubkey }.sorted().take(500) }
    val people = remember(candidates, typed) { (candidates + listOfNotNull(typed)).toSet() }
    val profiles by remember(people) {
        if (people.isEmpty()) flowOf(emptyMap()) else ProfileRepository.observe(people)
    }.collectAsState(emptyMap<String, NostrProfile>())
    val shown = remember(candidates, query, typed, profiles) {
        typed?.let(::listOf) ?: filterDmCandidates(candidates, emptyList(), query, nameOf = { profiles[it]?.bestName })
    }
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("新しい DM", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("フォロー中の人の名前、npub") },
                singleLine = true,
            )
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)
                    .verticalScroll(androidx.compose.foundation.rememberScrollState()),
            ) {
                if (shown.isEmpty()) {
                    Text(
                        if (query.isBlank()) "npub を貼り付けると、フォローしていない人にも送れます" else "見つかりません。npub を貼り付けて指定できます",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                shown.forEach { pubkey ->
                    val profile = profiles[pubkey]
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onPick(pubkey) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ChatAvatar(pubkey = pubkey, name = profile?.bestName, pictureUrl = profile?.picture, size = 32)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (pubkey == ownPubkey) "自分（メモ）" else profile?.bestName ?: (pubkey.take(8) + "…" + pubkey.takeLast(8)),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
