package com.nostr.torinos.ui.profile

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nostr.torinos.ui.components.NoteTimeline
import com.nostr.torinos.ui.feed.FeedViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyProfileScreen(
    ownPubkey: String,
    onBack: (() -> Unit)? = null,
    onOpenFollowing: () -> Unit = {},
    onOpenFollowers: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onUserClick: (String) -> Unit = {},
    onReply: ((eventId: String, authorPubkey: String) -> Unit)? = null,
    onOpenReplies: (eventId: String) -> Unit = {},
    onOpenLikes: (eventId: String) -> Unit = {},
    onOpenReposts: (eventId: String) -> Unit = {},
    viewModel: MyProfileViewModel = viewModel(
        factory = viewModelFactory { initializer { MyProfileViewModel(ownPubkey) } },
    ),
    feedViewModel: FeedViewModel = viewModel(key = "my-feed-$ownPubkey-reposts") {
        FeedViewModel(authorPubkey = ownPubkey, includeRepostsInFeed = true)
    },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val feedState by feedViewModel.state.collectAsStateWithLifecycle()
    var showEditSheet by remember { mutableStateOf(false) }
    val editProfileViewModel = viewModel<EditProfileViewModel>(
        key = "editProfile",
        factory = viewModelFactory { initializer { EditProfileViewModel() } },
    )

    LaunchedEffect(state.profile) {
        val profile = state.profile ?: return@LaunchedEffect
        feedViewModel.injectProfile(ownPubkey, profile)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("プロフィール") },
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
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "設定",
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                    IconButton(onClick = { showEditSheet = true }) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "プロフィール編集",
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
    ) { padding ->
        if (showEditSheet) {
            EditProfileSheet(
                onDismiss = { showEditSheet = false },
                viewModel = editProfileViewModel,
                onSaved = { viewModel.applyProfile(it) },
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
                .padding(padding),
            onReply = onReply,
            onOpenReplies = onOpenReplies,
            onOpenLikes = onOpenLikes,
            onOpenReposts = onOpenReposts,
            onRepost = feedViewModel::repost,
            onUnrepost = feedViewModel::unrepost,
            header = {
                item {
                    ProfileHeader(
                        pubkey = ownPubkey,
                        profile = state.profile,
                        isOwnProfile = true,
                    )
                    HorizontalDivider()
                }

                item {
                    ProfileStatsRow(
                        followingCount = state.followingCount,
                        followersCount = state.followersCount,
                        isFollowersLoading = state.isFollowersLoading,
                        onFetchFollowers = viewModel::fetchFollowers,
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
