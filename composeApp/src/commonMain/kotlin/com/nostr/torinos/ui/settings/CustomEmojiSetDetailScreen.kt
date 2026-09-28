package com.nostr.torinos.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nostr.torinos.emoji.CustomEmoji
import com.nostr.torinos.network.ProfileFetchPolicy
import com.nostr.torinos.network.ProfileRepository
import com.nostr.torinos.ui.components.AppTopBar
import com.nostr.torinos.ui.components.NetworkImage
import com.nostr.torinos.ui.components.ProfileNameText
import com.nostr.torinos.ui.profile.AvatarCircle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EmojiSetDetailScreen(
    title: String,
    emojis: List<CustomEmoji>,
    authorPubkey: String,
    initialShortcode: String = "",
    initialImageUrl: String = "",
    isRegistered: Boolean,
    canEdit: Boolean,
    isFavorite: (CustomEmoji) -> Boolean,
    onToggleFavorite: (CustomEmoji) -> Unit,
    onRegister: () -> Unit,
    onUnregister: () -> Unit,
    onBack: () -> Unit,
) {
    val authorProfile by ProfileRepository.observe(authorPubkey).collectAsState(
        initial = ProfileRepository.getCached(authorPubkey),
    )
    var selectedEmoji by remember(emojis, initialShortcode, initialImageUrl) {
        mutableStateOf(selectInitialEmoji(emojis, initialShortcode, initialImageUrl))
    }
    LaunchedEffect(authorPubkey) {
        if (authorPubkey.isNotBlank()) {
            ProfileRepository.ensureProfiles(
                pubkeys = setOf(authorPubkey),
                policy = ProfileFetchPolicy.CacheFirst(AuthorProfileMaxAgeMillis),
            )
        }
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "一覧に戻る",
                        )
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = if (isRegistered) onUnregister else onRegister,
                        enabled = canEdit,
                    ) {
                        Icon(
                            imageVector = if (isRegistered) Icons.Default.Delete else Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(if (isRegistered) "セットを登録解除" else "登録")
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "${emojis.size}個の絵文字",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                selectedEmoji?.let { emoji ->
                    NetworkImage(
                        url = emoji.imageUrl,
                        contentDescription = ":${emoji.shortcode}:",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(120.dp),
                    )
                    Text(
                        text = ":${emoji.shortcode}:",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    val favorite = isFavorite(emoji)
                    FilterChip(
                        selected = favorite,
                        onClick = { onToggleFavorite(emoji) },
                        enabled = canEdit,
                        label = {
                            Text(if (favorite) "お気に入り済み" else "お気に入りに追加")
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = if (favorite) Icons.Default.Star else Icons.Default.StarBorder,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                }
                if (authorPubkey.isNotBlank()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AvatarCircle(
                            pubkey = authorPubkey,
                            name = authorProfile?.bestName,
                            pictureUrl = authorProfile?.picture,
                            size = 36,
                        )
                        Column {
                            Text(
                                text = "作成者",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            ProfileNameText(
                                profile = authorProfile,
                                fallback = "名前未設定",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                } else {
                    Text(
                        text = "作成者不明",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 68.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                gridItems(
                    items = emojis,
                    key = { emoji -> "${emoji.shortcode}-${emoji.imageUrl}" },
                ) { emoji ->
                    Box(
                        modifier = Modifier
                            .size(68.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (selectedEmoji == emoji) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                            )
                            .clickable { selectedEmoji = emoji },
                        contentAlignment = Alignment.Center,
                    ) {
                        NetworkImage(
                            url = emoji.imageUrl,
                            contentDescription = emoji.shortcode,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(56.dp),
                        )
                    }
                }
            }
        }
    }
}

private const val AuthorProfileMaxAgeMillis = 60L * 60L * 1_000L
