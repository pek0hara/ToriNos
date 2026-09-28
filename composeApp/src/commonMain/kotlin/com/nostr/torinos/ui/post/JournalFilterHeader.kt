package com.nostr.torinos.ui.post

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.PostAdd
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.SouthWest
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nostr.torinos.journal.JournalActivityKind

internal val JournalActivityKind.label: String
    get() = when (this) {
        JournalActivityKind.Post -> "ポスト"
        JournalActivityKind.Repost -> "リポスト"
        JournalActivityKind.Reply -> "返信"
        JournalActivityKind.Like -> "したいいね"
        JournalActivityKind.ReceivedLike -> "もらったいいね"
    }

private val JournalActivityKind.icon: ImageVector
    get() = when (this) {
        JournalActivityKind.Post -> Icons.Default.PostAdd
        JournalActivityKind.Repost -> Icons.Default.Repeat
        JournalActivityKind.Reply -> Icons.Default.MailOutline
        JournalActivityKind.Like, JournalActivityKind.ReceivedLike -> Icons.Default.Favorite
    }

/** 保存しておいた種類名を復元する。今はない種類（旧「記事」など）は捨てる。 */
internal fun journalKindsFromNames(names: List<String>, available: Set<JournalActivityKind>): Set<JournalActivityKind> =
    names.mapNotNullTo(mutableSetOf()) { name -> JournalActivityKind.entries.firstOrNull { it.name == name } }
        .filterTo(mutableSetOf()) { it in available }

@Composable
internal fun JournalFilterHeader(
    kinds: List<JournalActivityKind>,
    selectedKinds: Set<JournalActivityKind>,
    defaultKinds: Set<JournalActivityKind>,
    onToggle: (JournalActivityKind) -> Unit,
) {
    val hasExplicitSelection = selectedKinds.isNotEmpty()
    val primary = MaterialTheme.colorScheme.primary
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(kinds) { kind ->
            val isImplicitlyShown = !hasExplicitSelection && kind in defaultKinds
            FilterChip(
                selected = kind in selectedKinds,
                onClick = { onToggle(kind) },
                modifier = Modifier.semantics { contentDescription = kind.label },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = if (isImplicitlyShown) {
                        primary.copy(alpha = 0.12f)
                    } else {
                        Color.Transparent
                    },
                    labelColor = if (isImplicitlyShown) {
                        primary.copy(alpha = 0.72f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    selectedContainerColor = primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                ),
                label = {
                    Box(
                        modifier = Modifier.size(36.dp, 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (kind == JournalActivityKind.Like || kind == JournalActivityKind.ReceivedLike) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Favorite,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                                Icon(
                                    imageVector = if (kind == JournalActivityKind.Like) {
                                        Icons.Default.NorthEast
                                    } else {
                                        Icons.Default.SouthWest
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        } else {
                            Icon(
                                imageVector = kind.icon,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                },
            )
        }
    }
}
