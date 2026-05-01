package com.nostr.torinos.ui.profile

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.ui.components.NoteTimeline
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
    onReply: ((eventId: String, authorPubkey: String, preview: String) -> Unit)? = null,
    onOpenReplies: (eventId: String) -> Unit = {},
    onOpenLikes: (eventId: String) -> Unit = {},
    onOpenReposts: (eventId: String) -> Unit = {},
) {
    val relays by RelayStore.relays.collectAsState(
        initial = RelayStore.defaults.filter { it.enabled }.map { it.url },
    )
    val contentRelayUrl = relays.firstOrNull()
    val viewModel: UserProfileViewModel = viewModel(key = "profile-$pubkey-${contentRelayUrl ?: "all"}") {
        UserProfileViewModel(pubkey, deferredRelayUrl = contentRelayUrl)
    }
    val feedViewModel: FeedViewModel = viewModel(key = "user-feed-$pubkey-${contentRelayUrl ?: "all"}") {
        FeedViewModel(
            authorPubkey = pubkey,
            relayUrl = contentRelayUrl,
            autoStart = false,
            includeRepostsInFeed = true,
        )
    }
    val state by viewModel.state.collectAsState()
    val feedState by feedViewModel.state.collectAsState()
    val displayName = state.profile?.bestName ?: (pubkey.take(8) + "…" + pubkey.takeLast(8))
    val snackbarHostState = remember { SnackbarHostState() }
    var deferredContentStarted by remember(pubkey) { mutableStateOf(false) }

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
                title = {
                    Text(
                        text = displayName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
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
        NoteTimeline(
            state = feedState,
            ownPubkey = ownPubkey,
            onUserClick = onUserClick,
            onLoadMore = feedViewModel::loadMore,
            onLike = feedViewModel::react,
            onUnlike = feedViewModel::unreact,
            onDelete = feedViewModel::deleteEvent,
            modifier = Modifier
                .padding(padding),
            onReply = onReply,
            onOpenReplies = onOpenReplies,
            onOpenLikes = onOpenLikes,
            onOpenReposts = onOpenReposts,
            onRepost = feedViewModel::repost,
            onUnrepost = feedViewModel::unrepost,
            emptyText = "このユーザーの投稿はありません",
            header = {
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
                    ProfileStatsRow(
                        followingCount = state.followingCount,
                        followersCount = state.followersCount,
                        followersCountSuffix = if (state.isFollowersCountLimited) "+" else "",
                        isFollowersLoading = state.isFollowersLoading,
                        onFetchFollowers = viewModel::loadFollowersCount,
                        followersFetched = state.followersLoaded,
                        onOpenFollowing = onOpenFollowing,
                        onOpenFollowers = onOpenFollowers,
                    )
                    HorizontalDivider()
                }
            },
        )
    }
}
