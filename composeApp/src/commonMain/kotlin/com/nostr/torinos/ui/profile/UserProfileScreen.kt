package com.nostr.torinos.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nostr.torinos.model.NostrProfile
import coil3.compose.LocalPlatformContext
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.ui.components.LinkedText
import com.nostr.torinos.ui.components.noteListItems
import com.nostr.torinos.ui.feed.FeedViewModel
import kotlinx.coroutines.delay

private const val DEFERRED_PROFILE_CONTENT_DELAY_MS = 800L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileScreen(
    pubkey: String,
    onBack: (() -> Unit)? = null,
    isOwnProfile: Boolean = false,
    ownPubkey: String? = null,
    onUserClick: (String) -> Unit = {},
    onOpenFollowing: (() -> Unit)? = null,
    onOpenFollowers: (() -> Unit)? = null,
) {
    val relays by RelayStore.relays.collectAsStateWithLifecycle(
        initialValue = RelayStore.defaults.filter { it.enabled }.map { it.url },
    )
    val contentRelayUrl = relays.firstOrNull()
    val viewModel: UserProfileViewModel = viewModel(key = "profile-$pubkey-${contentRelayUrl ?: "all"}") {
        UserProfileViewModel(pubkey, deferredRelayUrl = contentRelayUrl)
    }
    val feedViewModel: FeedViewModel = viewModel(key = "user-feed-$pubkey-${contentRelayUrl ?: "all"}") {
        FeedViewModel(authorPubkey = pubkey, relayUrl = contentRelayUrl, autoStart = false)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val feedState by feedViewModel.state.collectAsStateWithLifecycle()
    val displayName = state.profile?.bestName ?: (pubkey.take(8) + "…" + pubkey.takeLast(8))
    val snackbarHostState = remember { SnackbarHostState() }
    var deferredContentStarted by remember(pubkey) { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val reachedBottom by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            lastVisible != null && lastVisible.index >= listState.layoutInfo.totalItemsCount - 3
        }
    }
    LaunchedEffect(reachedBottom, feedState.canLoadMore) {
        if (reachedBottom && feedState.canLoadMore) feedViewModel.loadMore()
    }

    LaunchedEffect(state.profile) {
        val profile = state.profile ?: return@LaunchedEffect
        feedViewModel.injectProfile(pubkey, profile)
        if (!deferredContentStarted) {
            deferredContentStarted = true
            viewModel.loadFollowingCount()
            feedViewModel.startSubscriptions()
        }
    }

    LaunchedEffect(pubkey) {
        delay(DEFERRED_PROFILE_CONTENT_DELAY_MS)
        if (!deferredContentStarted) {
            deferredContentStarted = true
            viewModel.loadFollowingCount()
            feedViewModel.startSubscriptions()
        }
    }

    LaunchedEffect(state.followError) {
        if (state.followError != null) {
            snackbarHostState.showSnackbar(state.followError!!)
            viewModel.clearFollowError()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(displayName) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "戻る",
                                tint = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item {
                ProfileHeader(
                    pubkey = pubkey,
                    profile = state.profile,
                    isOwnProfile = isOwnProfile,
                    isFollowing = state.isFollowing,
                    isFollowLoading = state.isFollowLoading,
                    canFollow = state.canFollow,
                    onFollow = viewModel::follow,
                    onUnfollow = viewModel::unfollow,
                )
                HorizontalDivider()
            }

            item {
                Row(modifier = Modifier.fillMaxWidth()) {
                    UserStatCell(
                        label = "フォロー",
                        count = state.followingCount,
                        modifier = Modifier
                            .weight(1f)
                            .then(if (onOpenFollowing != null) Modifier.clickable { onOpenFollowing() } else Modifier),
                    )
                    UserStatCell(
                        label = "フォロワー",
                        count = state.followersCount,
                        countSuffix = if (state.isFollowersCountLimited) "+" else "",
                        isLoading = state.isFollowersLoading,
                        onFetch = viewModel::loadFollowersCount,
                        fetched = state.followersLoaded,
                        modifier = Modifier
                            .weight(1f)
                            .then(if (onOpenFollowers != null) Modifier.clickable { onOpenFollowers() } else Modifier),
                    )
                }
                HorizontalDivider()
            }

            noteListItems(
                state = feedState,
                ownPubkey = ownPubkey,
                onUserClick = onUserClick,
                onLike = feedViewModel::react,
                onUnlike = feedViewModel::unreact,
                onDelete = feedViewModel::deleteEvent,
                emptyText = "このユーザーの投稿はありません",
            )
        }
    }
}

@Composable
private fun ProfileHeader(
    pubkey: String,
    profile: NostrProfile?,
    isOwnProfile: Boolean,
    isFollowing: Boolean?,
    isFollowLoading: Boolean,
    canFollow: Boolean,
    onFollow: () -> Unit,
    onUnfollow: () -> Unit,
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
        if (!profile?.about.isNullOrBlank()) {
            LinkedText(
                text = profile!!.about!!,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!profile?.nip05.isNullOrBlank()) {
            Text(
                text = profile!!.nip05!!,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
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
