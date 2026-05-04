package com.nostr.torinos.ui.profile

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nostr.torinos.network.MuteStore
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.ui.components.NoteTimeline
import com.nostr.torinos.ui.feed.FeedViewModel
import kotlinx.coroutines.delay

private const val DEFERRED_PROFILE_CONTENT_DELAY_MS = 800L

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
            includeRepliesInFeed = true,
        )
    }
    val state by viewModel.state.collectAsState()
    val feedState by feedViewModel.state.collectAsState()
    val mutedPubkeys by MuteStore.mutedPubkeys.collectAsState()
    val isMuted = mutedPubkeys.contains(pubkey)
    val snackbarHostState = remember { SnackbarHostState() }
    var deferredContentStarted by remember(pubkey) { mutableStateOf(false) }
    var showRelayList by remember(pubkey) { mutableStateOf(false) }

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
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (showRelayList) {
            ProfileRelayListDialog(
                relayUrls = state.relayUrls,
                onDismiss = { showRelayList = false },
            )
        }
        NoteTimeline(
            state = feedState,
            ownPubkey = ownPubkey,
            onUserClick = onUserClick,
            onLoadMore = feedViewModel::loadMore,
            onLike = feedViewModel::react,
            onUnlike = feedViewModel::unreact,
            onDelete = feedViewModel::deleteEvent,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            onReply = onReply,
            onOpenReplies = onOpenReplies,
            onOpenLikes = onOpenLikes,
            onOpenReposts = onOpenReposts,
            onRepost = feedViewModel::repost,
            onUnrepost = feedViewModel::unrepost,
            emptyText = "このユーザーのポストはありません",
            header = {
                item {
                    ProfileHeader(
                        pubkey = pubkey,
                        profile = state.profile,
                        linkedProfiles = state.linkedProfiles,
                        isOwnProfile = isOwnProfile,
                        isFollowing = state.isFollowing,
                        isFollowLoading = state.isFollowLoading,
                        canFollow = state.canFollow,
                        isMuted = isMuted,
                        relayUrls = state.relayUrls,
                        onFollow = viewModel::follow,
                        onUnfollow = viewModel::unfollow,
                        onMuteToggle = {
                            if (isMuted) MuteStore.unmute(pubkey) else MuteStore.mute(pubkey)
                        },
                        onUserClick = onUserClick,
                        onBack = onBack,
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
                        relayCount = state.relayUrls.size,
                        onOpenFollowing = onOpenFollowing,
                        onOpenFollowers = onOpenFollowers,
                        onOpenRelays = { showRelayList = true },
                    )
                    HorizontalDivider()
                }
            },
        )
    }
}
