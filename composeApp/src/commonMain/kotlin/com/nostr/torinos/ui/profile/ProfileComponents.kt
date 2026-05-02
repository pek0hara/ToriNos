package com.nostr.torinos.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.LocalPlatformContext
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.ui.components.LinkedText

@Composable
internal fun ProfileHeader(
    pubkey: String,
    profile: NostrProfile?,
    linkedProfiles: Map<String, NostrProfile> = emptyMap(),
    isOwnProfile: Boolean,
    isFollowing: Boolean? = null,
    isFollowLoading: Boolean = false,
    canFollow: Boolean = false,
    onFollow: () -> Unit = {},
    onUnfollow: () -> Unit = {},
    onUserClick: (String) -> Unit = {},
) {
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

@Composable
internal fun ProfileStatsRow(
    followingCount: Int,
    followersCount: Int,
    modifier: Modifier = Modifier,
    followersCountSuffix: String = "",
    isFollowersLoading: Boolean = false,
    onFetchFollowers: (() -> Unit)? = null,
    followersFetched: Boolean = true,
    onOpenFollowing: (() -> Unit)? = null,
    onOpenFollowers: (() -> Unit)? = null,
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
