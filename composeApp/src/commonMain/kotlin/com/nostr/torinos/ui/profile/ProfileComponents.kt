package com.nostr.torinos.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Settings
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
import com.nostr.torinos.ui.components.NetworkImage
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
    isMuted: Boolean = false,
    relayUrls: List<String> = emptyList(),
    onFollow: () -> Unit = {},
    onUnfollow: () -> Unit = {},
    onMuteToggle: () -> Unit = {},
    onUserClick: (String) -> Unit = {},
    onBack: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    onEditBanner: (() -> Unit)? = null,
    onEditAvatar: (() -> Unit)? = null,
    onEditName: (() -> Unit)? = null,
    onEditAbout: (() -> Unit)? = null,
) {
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    val npub = remember(pubkey) { hexToNpub(pubkey) }
    var pubkeyCopied by remember(pubkey) { mutableStateOf(false) }
    val hasBannerArea = !profile?.banner.isNullOrBlank() ||
        (isOwnProfile && onEditBanner != null) ||
        onBack != null ||
        (isOwnProfile && onOpenSettings != null)

    LaunchedEffect(pubkeyCopied) {
        if (pubkeyCopied) {
            delay(2_000)
            pubkeyCopied = false
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (hasBannerArea) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(178.dp),
            ) {
                ProfileBanner(
                    bannerUrl = profile?.banner,
                    isOwnProfile = isOwnProfile,
                    onEditBanner = onEditBanner,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(132.dp),
                )
                ProfileAvatar(
                    pubkey = pubkey,
                    profile = profile,
                    size = 96,
                    onEditAvatar = if (isOwnProfile) onEditAvatar else null,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 16.dp),
                )
                FollowActionRow(
                    isOwnProfile = isOwnProfile,
                    canFollow = canFollow,
                    isFollowLoading = isFollowLoading,
                    isFollowing = isFollowing,
                    isMuted = isMuted,
                    onFollow = onFollow,
                    onUnfollow = onUnfollow,
                    onMuteToggle = onMuteToggle,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp),
                )
                if (onBack != null) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .statusBarsPadding()
                            .padding(start = 8.dp, top = 8.dp)
                            .background(Color.Black.copy(alpha = 0.35f), CircleShape),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "戻る",
                            tint = Color.White,
                        )
                    }
                }
                if (isOwnProfile && onOpenSettings != null) {
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .statusBarsPadding()
                            .padding(end = 8.dp, top = 8.dp)
                            .background(Color.Black.copy(alpha = 0.35f), CircleShape),
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "設定",
                            tint = Color.White,
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!hasBannerArea) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ProfileAvatar(
                        pubkey = pubkey,
                        profile = profile,
                        size = 64,
                        onEditAvatar = if (isOwnProfile) onEditAvatar else null,
                    )
                    FollowActionRow(
                        isOwnProfile = isOwnProfile,
                        canFollow = canFollow,
                        isFollowLoading = isFollowLoading,
                        isFollowing = isFollowing,
                        isMuted = isMuted,
                        onFollow = onFollow,
                        onUnfollow = onUnfollow,
                        onMuteToggle = onMuteToggle,
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = profile?.bestName ?: (pubkey.take(8) + "…" + pubkey.takeLast(8)),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (isOwnProfile && onEditName != null) {
                    IconButton(onClick = onEditName, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "名前を編集",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
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
            if (isOwnProfile && profile?.about.isNullOrBlank() && onEditAbout != null) {
                TextButton(onClick = onEditAbout, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(4.dp))
                    Text("自己紹介を追加", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                profile?.about?.takeIf { it.isNotBlank() }?.let { about ->
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        LinkedText(
                            text = about,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            onProfileClick = onUserClick,
                            profiles = linkedProfiles,
                        )
                        if (isOwnProfile && onEditAbout != null) {
                            IconButton(onClick = onEditAbout, modifier = Modifier.size(28.dp)) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = "自己紹介を編集",
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
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
}

@Composable
private fun ProfileBanner(
    bannerUrl: String?,
    isOwnProfile: Boolean,
    onEditBanner: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    if (bannerUrl.isNullOrBlank() && (!isOwnProfile || onEditBanner == null)) return

    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (!bannerUrl.isNullOrBlank()) {
            NetworkImage(
                url = bannerUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (isOwnProfile && onEditBanner != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(30.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                    .clickable(onClick = onEditBanner),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "バナーを変更",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

@Composable
private fun ProfileAvatar(
    pubkey: String,
    profile: NostrProfile?,
    size: Int,
    onEditAvatar: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.size(size.dp)) {
        val avatarFrameModifier = if (onEditAvatar != null && size >= 80) {
            Modifier.border(4.dp, MaterialTheme.colorScheme.surface, CircleShape)
        } else {
            Modifier
        }
        AvatarCircle(
            pubkey = pubkey,
            name = profile?.bestName,
            pictureUrl = profile?.picture,
            size = size,
            modifier = avatarFrameModifier,
        )
        if (onEditAvatar != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = if (size >= 80) 4.dp else 2.dp, bottom = if (size >= 80) 4.dp else 2.dp)
                    .size(if (size >= 80) 28.dp else 22.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                    .clickable(onClick = onEditAvatar),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "アイコンを変更",
                    modifier = Modifier.size(if (size >= 80) 16.dp else 13.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

@Composable
private fun FollowActionRow(
    isOwnProfile: Boolean,
    canFollow: Boolean,
    isFollowLoading: Boolean,
    isFollowing: Boolean?,
    isMuted: Boolean,
    onFollow: () -> Unit,
    onUnfollow: () -> Unit,
    onMuteToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (isOwnProfile || !canFollow) return

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
        IconButton(onClick = onMuteToggle) {
            Icon(
                imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = if (isMuted) "ミュートを解除" else "ミュート",
                tint = if (isMuted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
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
            modifier = modifier
                .size(size.dp)
                .clip(CircleShape)
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
