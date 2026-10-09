package com.nostr.torinos.ui.post

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.decodeNpub
import com.nostr.torinos.ui.profile.AvatarCircle

/**
 * ジャーナルに載せるメンバーの行。タップで選択を切り替え、長押しで外す。
 * 種類のフィルターと同じく、何も選んでいないときは既定で表示する先頭のメンバーを薄く塗る。
 * [removablePubkey]が false のメンバー（先頭）には外すメニューを出さない。
 */
@Composable
internal fun JournalMemberBar(
    members: List<String>,
    selectedMembers: Set<String>,
    profiles: Map<String, NostrProfile>,
    removablePubkey: (String) -> Boolean,
    canAdd: Boolean,
    onToggle: (String) -> Unit,
    onRemove: (String) -> Unit,
    onAdd: () -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(members, key = { it }) { pubkey ->
            JournalMemberChip(
                pubkey = pubkey,
                profile = profiles[pubkey],
                selected = pubkey in selectedMembers,
                isImplicitlyShown = selectedMembers.isEmpty() && pubkey == members.firstOrNull(),
                removable = removablePubkey(pubkey),
                onToggle = { onToggle(pubkey) },
                onRemove = { onRemove(pubkey) },
            )
        }
        item(key = "add") {
            Surface(
                onClick = onAdd,
                enabled = canAdd,
                shape = RoundedCornerShape(8.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.semantics {
                    contentDescription = if (canAdd) {
                        "ジャーナルにメンバーを追加"
                    } else {
                        "メンバーは${JOURNAL_MAX_ADDED_MEMBERS}人まで追加できます"
                    }
                },
            ) {
                Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = if (canAdd) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun JournalMemberChip(
    pubkey: String,
    profile: NostrProfile?,
    selected: Boolean,
    isImplicitlyShown: Boolean,
    removable: Boolean,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val name = profile?.bestName ?: shortPubkey(pubkey)
    val primary = MaterialTheme.colorScheme.primary
    val visible = selected || isImplicitlyShown
    Box {
        Surface(
            shape = RoundedCornerShape(8.dp),
            // 種類のフィルターのチップに色をそろえる。
            color = when {
                selected -> primary
                isImplicitlyShown -> primary.copy(alpha = 0.12f)
                else -> Color.Transparent
            },
            contentColor = when {
                selected -> MaterialTheme.colorScheme.onPrimary
                isImplicitlyShown -> primary.copy(alpha = 0.72f)
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .combinedClickable(
                    onClick = onToggle,
                    onLongClick = if (removable) {
                        { showMenu = true }
                    } else {
                        null
                    },
                )
                .semantics {
                    contentDescription = if (visible) "$name を表示中" else "$name を非表示"
                },
        ) {
            Row(
                modifier = Modifier
                    .height(32.dp)
                    .padding(start = 4.dp, end = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AvatarCircle(
                    pubkey = pubkey,
                    name = profile?.bestName,
                    pictureUrl = profile?.picture,
                    size = 24,
                    // 隠しているメンバーは顔も薄くして、表示中と見分けやすくする。
                    modifier = if (visible) Modifier else Modifier.alpha(0.45f),
                )
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 96.dp),
                )
            }
        }
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
                text = { Text("ジャーナルから外す") },
                onClick = {
                    showMenu = false
                    onRemove()
                },
            )
        }
    }
}

/**
 * メンバーを追加するシート。直近のやりとりが多い順に候補を並べ、名前で絞り込める。
 * npub か 16進の公開鍵を入れれば、候補にいない人も追加できる。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JournalMemberSheet(
    candidates: JournalMemberCandidates,
    members: List<String>,
    profiles: Map<String, NostrProfile>,
    rankingLabel: String,
    onLoadCandidates: () -> Unit,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { onLoadCandidates() }

    val trimmed = query.trim()
    val directPubkey = remember(trimmed) { parsePubkeyInput(trimmed) }?.takeIf { it !in members }
    val filtered = candidates.candidates
        .filter { it.pubkey !in members }
        .filter { candidate ->
            trimmed.isEmpty() || directPubkey != null || profiles[candidate.pubkey].matchesQuery(trimmed)
        }
        .filter { directPubkey == null || it.pubkey == directPubkey }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 320.dp, max = 640.dp)
                .imePadding()
                .navigationBarsPadding(),
        ) {
            Text(
                text = "ジャーナルにメンバーを追加",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                placeholder = { Text("名前・npubで絞り込む") },
            )
            Text(
                text = rankingLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            )
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                if (directPubkey != null && filtered.none { it.pubkey == directPubkey }) {
                    item(key = "direct-$directPubkey") {
                        JournalMemberCandidateRow(
                            pubkey = directPubkey,
                            profile = profiles[directPubkey],
                            interactionCount = null,
                            onAdd = { onAdd(directPubkey) },
                        )
                    }
                }
                items(filtered, key = { it.pubkey }) { candidate ->
                    JournalMemberCandidateRow(
                        pubkey = candidate.pubkey,
                        profile = profiles[candidate.pubkey],
                        interactionCount = candidate.interactionCount,
                        onAdd = { onAdd(candidate.pubkey) },
                    )
                }
                when {
                    candidates.isLoading -> item(key = "loading") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                    filtered.isEmpty() && directPubkey == null -> item(key = "empty") {
                        Text(
                            text = if (trimmed.isEmpty()) "最近やりとりした人が見つかりませんでした" else "該当する人がいません",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun JournalMemberCandidateRow(
    pubkey: String,
    profile: NostrProfile?,
    interactionCount: Int?,
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onAdd)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarCircle(pubkey = pubkey, name = profile?.bestName, pictureUrl = profile?.picture, size = 40)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile?.bestName ?: shortPubkey(pubkey),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (interactionCount != null) {
                Text(
                    text = "やりとり ${interactionCount}件",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(onClick = onAdd) { Text("追加") }
    }
}

private fun NostrProfile?.matchesQuery(query: String): Boolean {
    if (this == null) return false
    return listOfNotNull(name, displayName, nip05).any { it.contains(query, ignoreCase = true) }
}

/** npub か 64桁の16進を公開鍵として読む。 */
internal fun parsePubkeyInput(input: String): String? {
    val value = input.removePrefix("nostr:")
    if (value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return value.lowercase()
    return if (value.startsWith("npub1")) decodeNpub(value) else null
}

private fun shortPubkey(pubkey: String): String = pubkey.take(8) + "…" + pubkey.takeLast(8)
