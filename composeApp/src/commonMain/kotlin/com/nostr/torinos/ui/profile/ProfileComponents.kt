package com.nostr.torinos.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import coil3.compose.LocalPlatformContext
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nostr.torinos.crypto.hexToNpub
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.ui.components.LinkedText
import com.nostr.torinos.ui.settings.setPlainText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun ProfileHeader(
    pubkey: String,
    profile: NostrProfile?,
    linkedProfiles: Map<String, NostrProfile> = emptyMap(),
    isOwnProfile: Boolean,
    isFollowing: Boolean? = null,
    isFollowLoading: Boolean = false,
    canFollow: Boolean = false,
    relayUrls: List<String> = emptyList(),
    onFollow: () -> Unit = {},
    onUnfollow: () -> Unit = {},
    onUserClick: (String) -> Unit = {},
) {
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    val npub = remember(pubkey) { hexToNpub(pubkey) }
    var pubkeyCopied by remember(pubkey) { mutableStateOf(false) }

    LaunchedEffect(pubkeyCopied) {
        if (pubkeyCopied) {
            delay(2_000)
            pubkeyCopied = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AvatarCircle(pubkey = pubkey, name = profile?.bestName, pictureUrl = profile?.picture, size = 64)

            if (!isOwnProfile && canFollow) {
                if (isFollowLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else if (isFollowing == true) {
                    OutlinedButton(onClick = onUnfollow) {
                        Text("フォロー中")
                    }
                } else if (isFollowing == false) {
                    Button(onClick = onFollow) {
                        Text("フォロー")
                    }
                }
            }
        }

        Text(
            text = profile?.bestName ?: (pubkey.take(8) + "…" + pubkey.takeLast(8)),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(6.dp),
                )
                .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = npub.shortKey(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    coroutineScope.launch {
                        clipboard.setPlainText(npub)
                        pubkeyCopied = true
                    }
                },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = if (pubkeyCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                    contentDescription = "公開鍵をコピー",
                    modifier = Modifier.size(16.dp),
                    tint = if (pubkeyCopied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        profile?.about?.takeIf { it.isNotBlank() }?.let { about ->
            LinkedText(
                text = about,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                onProfileClick = onUserClick,
                profiles = linkedProfiles,
            )
        }
        profile?.nip05?.takeIf { it.isNotBlank() }?.let { nip05 ->
            Text(
                text = nip05,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private fun String.shortKey(): String =
    if (length <= 20) this else take(12) + "…" + takeLast(8)

@Composable
internal fun ProfileStatsRow(
    followingCount: Int,
    followersCount: Int,
    modifier: Modifier = Modifier,
    followersCountSuffix: String = "",
    isFollowersLoading: Boolean = false,
    onFetchFollowers: (() -> Unit)? = null,
    followersFetched: Boolean = true,
    relayCount: Int = 0,
    onOpenFollowing: (() -> Unit)? = null,
    onOpenFollowers: (() -> Unit)? = null,
    onOpenRelays: (() -> Unit)? = null,
) {
    Row(modifier = modifier.fillMaxWidth()) {
        UserStatCell(
            label = "フォロー",
            count = followingCount,
            modifier = Modifier
                .weight(1f)
                .then(if (onOpenFollowing != null) Modifier.clickable { onOpenFollowing() } else Modifier),
        )
        UserStatCell(
            label = "フォロワー",
            count = followersCount,
            countSuffix = followersCountSuffix,
            isLoading = isFollowersLoading,
            onFetch = onFetchFollowers,
            fetched = followersFetched,
            modifier = Modifier
                .weight(1f)
                .then(if (onOpenFollowers != null) Modifier.clickable { onOpenFollowers() } else Modifier),
        )
        UserStatCell(
            label = "リレー",
            count = relayCount,
            modifier = Modifier
                .weight(1f)
                .then(if (onOpenRelays != null) Modifier.clickable { onOpenRelays() } else Modifier),
        )
    }
}

@Composable
internal fun UserStatCell(
    label: String,
    count: Int,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    countSuffix: String = "",
    onFetch: (() -> Unit)? = null,
    fetched: Boolean = true,
) {
    Column(
        modifier = modifier.padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        when {
            isLoading -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            onFetch != null && !fetched -> IconButton(
                onClick = onFetch,
                modifier = Modifier.size(20.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.FileDownload,
                    contentDescription = "フォロワー数を取得",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            else -> Text(
                text = count.toString() + countSuffix,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun ProfileRelayListDialog(
    relayUrls: List<String>,
    onDismiss: () -> Unit,
) {
    val relayEntries by RelayStore.entries.collectAsState()
    val savedRelayUrls = remember(relayEntries) { relayEntries.map { it.url }.toSet() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("参加リレー") },
        text = {
            if (relayUrls.isEmpty()) {
                Text(
                    text = "参加リレーは公開されていません",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn {
                    items(relayUrls, key = { it }) { url ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = url,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (url in savedRelayUrls) {
                                Text(
                                    text = "追加済み",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                TextButton(onClick = { RelayStore.add(url) }) {
                                    Text("追加")
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("閉じる")
            }
        },
    )
}

@Composable
internal fun AvatarCircle(pubkey: String, name: String?, pictureUrl: String? = null, size: Int = 38, modifier: Modifier = Modifier) {
    val letter = name?.firstOrNull()?.uppercaseChar()
        ?: pubkey.firstOrNull()?.uppercaseChar()
        ?: '?'
    val fallback: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .size(size.dp)
                .background(avatarColor(pubkey), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = letter.toString(),
                color = Color.White,
                style = if (size >= 48) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }

    if (!pictureUrl.isNullOrBlank()) {
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(LocalPlatformContext.current)
                .data(pictureUrl)
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size.dp)
                .clip(CircleShape),
            error = { fallback() },
            loading = { fallback() },
        )
    } else {
        fallback()
    }
}

private val avatarPalette = listOf(
    Color(0xFF6B4EFF), Color(0xFFFF6B6B), Color(0xFF4ECDC4),
    Color(0xFF45B7D1), Color(0xFF96CEB4), Color(0xFFFF9F43),
    Color(0xFFA29BFE), Color(0xFFE17055), Color(0xFF00B894),
)

internal fun avatarColor(pubkey: String): Color {
    val index = pubkey.hashCode().mod(avatarPalette.size)
    return avatarPalette[index]
}
