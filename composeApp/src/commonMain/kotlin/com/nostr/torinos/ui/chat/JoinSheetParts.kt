package com.nostr.torinos.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nostr.torinos.chat.GroupLayout
import com.nostr.torinos.chat.GroupRef
import com.nostr.torinos.chat.PublicGroupEntry

/** 参加シートの見出し（アイコン・タイトル・説明）。 */
@Composable
internal fun JoinSheetHeader() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Groups, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text("グループに参加", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "公開グループを探すか、招待リンク・アドレスから参加します",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 検索欄の下の、貼り付けで使える入力の例。押すとクリップボードから読む。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun JoinInputKinds(enabled: Boolean, onPaste: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        JoinKindChip("招待リンク", Icons.Outlined.Link, enabled, onPaste)
        JoinKindChip("naddr", Icons.Outlined.Tag, enabled, onPaste)
        JoinKindChip("リレー (wss://)", Icons.Outlined.Dns, enabled, onPaste)
    }
}

@Composable
private fun JoinKindChip(label: String, icon: ImageVector, enabled: Boolean, onPaste: () -> Unit) {
    AssistChip(
        onClick = onPaste,
        enabled = enabled,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
        shape = RoundedCornerShape(50),
    )
}

/** 参加シートの、公開グループの一覧（検索結果）。 */
@Composable
internal fun PublicGroupResults(
    results: List<PublicGroupEntry>,
    loading: Boolean,
    query: String,
    enabled: Boolean,
    onPick: (GroupRef) -> Unit,
    onPaste: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)) {
        Icon(
            Icons.Outlined.Explore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "公開グループ",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        if (results.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(
                    "${results.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
    if (loading) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).padding(bottom = 4.dp))
    }
    if (results.isEmpty() && !loading) {
        JoinEmptyState(searching = query.isNotBlank(), enabled = enabled, onPaste = onPaste)
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(results.take(PUBLIC_GROUP_RESULT_LIMIT), key = { it.ref.key }) { entry ->
            PublicGroupCard(entry, enabled, onPick)
        }
    }
}

@Composable
private fun PublicGroupCard(entry: PublicGroupEntry, enabled: Boolean, onPick: (GroupRef) -> Unit) {
    val forum = entry.metadata.layout == GroupLayout.Forum
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(enabled = enabled) { onPick(entry.ref) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
            GroupIcon(entry.metadata.picture, locked = false, size = 44, forum = forum)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.metadata.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (forum) {
                        Spacer(Modifier.width(6.dp))
                        SmallTag("フォーラム")
                    }
                }
                val about = entry.metadata.about.lineSequence().firstOrNull { it.isNotBlank() }
                if (about != null) {
                    Text(
                        about,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Icon(
                        Icons.Outlined.Dns,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        entry.ref.relayUrl.substringAfter("://").trimEnd('/'),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SmallTag(text: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
    }
}

/** 公開グループが無い・見つからないとき。招待リンクの貼り付けへ誘う。 */
@Composable
private fun JoinEmptyState(searching: Boolean, enabled: Boolean, onPaste: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
        ) {
            Box(
                modifier = Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (searching) Icons.Default.Search else Icons.Outlined.Explore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                if (searching) "見つかりませんでした" else "公開グループはまだありません",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (searching) {
                    "別の名前で探すか、招待リンク・アドレスを貼り付けてください"
                } else {
                    "登録しているリレーに公開グループがありません。招待リンクやリレーのアドレスから参加できます"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(onClick = onPaste, enabled = enabled) {
                Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("貼り付けて参加")
            }
        }
    }
}

private const val PUBLIC_GROUP_RESULT_LIMIT = 200
