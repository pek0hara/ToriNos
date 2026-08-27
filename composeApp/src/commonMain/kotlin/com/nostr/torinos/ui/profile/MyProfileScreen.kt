package com.nostr.torinos.ui.profile

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nostr.torinos.account.accountSessionViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nostr.torinos.ui.components.NoteTimeline
import com.nostr.torinos.ui.feed.FeedViewModel

@Composable
fun MyProfileScreen(
    ownPubkey: String,
    onBack: (() -> Unit)? = null,
    onOpenFollowing: () -> Unit = {},
    onOpenFollowers: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onUserClick: (String) -> Unit = {},
    onReply: ((eventId: String, authorPubkey: String, preview: String) -> Unit)? = null,
    onOpenReplies: (eventId: String) -> Unit = {},
    onOpenLikes: (eventId: String) -> Unit = {},
    onOpenReposts: (eventId: String) -> Unit = {},
    viewModel: MyProfileViewModel = accountSessionViewModel(
        key = "my-profile-$ownPubkey",
    ) { accountSession -> MyProfileViewModel(ownPubkey, accountSession) },
) {
    val postsViewModel: FeedViewModel = accountSessionViewModel(
        key = "my-feed-$ownPubkey-posts",
    ) { accountSession ->
        FeedViewModel(
            accountSession = accountSession,
            authorPubkey = ownPubkey,
            autoStart = false,
            includeRepostsInFeed = true,
            includeRepliesInFeed = false,
        )
    }
    val postsAndRepliesViewModel: FeedViewModel = accountSessionViewModel(
        key = "my-feed-$ownPubkey-posts-replies",
    ) { accountSession ->
        FeedViewModel(
            accountSession = accountSession,
            authorPubkey = ownPubkey,
            autoStart = false,
            includeRepostsInFeed = true,
            includeRepliesInFeed = true,
        )
    }

    val state by viewModel.state.collectAsState()
    val postsState by postsViewModel.state.collectAsState()
    val postsAndRepliesState by postsAndRepliesViewModel.state.collectAsState()
    var showRelayList by remember(ownPubkey) { mutableStateOf(false) }
    var showBannerEdit by remember(ownPubkey) { mutableStateOf(false) }
    var showAvatarEdit by remember(ownPubkey) { mutableStateOf(false) }
    var showNameEdit by remember(ownPubkey) { mutableStateOf(false) }
    var showAboutEdit by remember(ownPubkey) { mutableStateOf(false) }
    var showStatusEdit by remember(ownPubkey) { mutableStateOf(false) }
    var bannerHeightPx by remember(ownPubkey) { mutableIntStateOf(0) }
    var selectedTabName by rememberSaveable(ownPubkey) {
        mutableStateOf(ProfileTimelineTab.Posts.name)
    }
    val selectedTab = ProfileTimelineTab.entries.firstOrNull { it.name == selectedTabName }
        ?: ProfileTimelineTab.Posts
    val editProfileViewModel = accountSessionViewModel<EditProfileViewModel>(
        key = "edit-profile-$ownPubkey",
    ) { accountSession -> EditProfileViewModel(accountSession = accountSession) }
    val profileListState = rememberSaveable(ownPubkey, saver = LazyListState.Saver) {
        LazyListState()
    }

    LaunchedEffect(viewModel) {
        viewModel.refreshProfile()
    }

    LaunchedEffect(state.profile) {
        val profile = state.profile ?: return@LaunchedEffect
        postsViewModel.injectProfile(ownPubkey, profile)
        postsAndRepliesViewModel.injectProfile(ownPubkey, profile)
    }

    LaunchedEffect(selectedTab, postsViewModel, postsAndRepliesViewModel) {
        when (selectedTab) {
            ProfileTimelineTab.Posts -> postsViewModel.startSubscriptions()
            ProfileTimelineTab.PostsAndReplies -> postsAndRepliesViewModel.startSubscriptions()
        }
    }

    LaunchedEffect(state.generalStatusPublishCompletedCount) {
        if (state.generalStatusPublishCompletedCount > 0) {
            showStatusEdit = false
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        if (showAvatarEdit) {
            AvatarEditDialog(
                currentProfile = state.profile,
                pubkey = ownPubkey,
                viewModel = editProfileViewModel,
                onDismiss = { showAvatarEdit = false },
                onSaved = { viewModel.applyProfile(it) },
            )
        }
        if (showBannerEdit) {
            BannerEditDialog(
                currentProfile = state.profile,
                viewModel = editProfileViewModel,
                onDismiss = { showBannerEdit = false },
                onSaved = { viewModel.applyProfile(it) },
            )
        }
        if (showNameEdit) {
            NameEditDialog(
                currentProfile = state.profile,
                viewModel = editProfileViewModel,
                onDismiss = { showNameEdit = false },
                onSaved = { viewModel.applyProfile(it) },
            )
        }
        if (showAboutEdit) {
            AboutEditDialog(
                currentProfile = state.profile,
                viewModel = editProfileViewModel,
                onDismiss = { showAboutEdit = false },
                onSaved = { viewModel.applyProfile(it) },
            )
        }
        if (showRelayList) {
            ProfileRelayListDialog(
                relayUrls = state.relayUrls,
                onDismiss = { showRelayList = false },
            )
        }
        if (showStatusEdit) {
            GeneralStatusEditDialog(
                currentStatus = state.generalStatus?.content.orEmpty(),
                isPublishing = state.isGeneralStatusPublishing,
                errorMessage = state.generalStatusError,
                onDismiss = {
                    showStatusEdit = false
                    viewModel.clearGeneralStatusError()
                },
                onSave = viewModel::publishGeneralStatus,
            )
        }

        val profileHeader: LazyListScope.() -> Unit = {
            item {
                ProfileHeader(
                    pubkey = ownPubkey,
                    profile = state.profile,
                    linkedProfiles = state.linkedProfiles,
                    isOwnProfile = true,
                    relayUrls = state.relayUrls,
                    generalStatus = state.generalStatus,
                    onEditGeneralStatus = { showStatusEdit = true },
                    onUserClick = onUserClick,
                    onBack = onBack,
                    onEditBanner = { showBannerEdit = true },
                    onEditAvatar = { showAvatarEdit = true },
                    onEditName = { showNameEdit = true },
                    onEditAbout = { showAboutEdit = true },
                    showBackButton = false,
                    onBannerHeightChanged = { bannerHeightPx = it },
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
                    relayCount = state.relayUrls.size,
                    onOpenFollowing = onOpenFollowing,
                    onOpenFollowers = onOpenFollowers,
                    onOpenRelays = { showRelayList = true },
                )
                HorizontalDivider()
            }

            item {
                PrimaryTabRow(selectedTabIndex = selectedTab.ordinal) {
                    ProfileTimelineTab.entries.forEach { tab ->
                        Tab(
                            selected = selectedTab == tab,
                            onClick = { selectedTabName = tab.name },
                            text = { Text(tab.label) },
                        )
                    }
                }
                HorizontalDivider()
            }
        }

        ProfileTimelineWithCollapsingHeader(
            listState = profileListState,
            pubkey = ownPubkey,
            profile = state.profile,
            onBack = onBack,
            onOpenSettings = onOpenSettings,
            bannerHeightPx = bannerHeightPx,
        ) {
            when (selectedTab) {
                ProfileTimelineTab.Posts -> NoteTimeline(
                    state = postsState,
                    ownPubkey = ownPubkey,
                    onUserClick = onUserClick,
                    onLoadMore = postsViewModel::loadMore,
                    onLike = postsViewModel::react,
                    onUnlike = postsViewModel::unreact,
                    onEmojiReact = postsViewModel::reactWithEmoji,
                    onEmojiUnreact = postsViewModel::unreactWithEmoji,
                    onDelete = postsViewModel::deleteEvent,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    onReply = onReply,
                    onOpenReplies = onOpenReplies,
                    onOpenLikes = onOpenLikes,
                    onOpenReposts = onOpenReposts,
                    onRepost = postsViewModel::repost,
                    onUnrepost = postsViewModel::unrepost,
                    onReport = postsViewModel::reportEvent,
                    listState = profileListState,
                    header = profileHeader,
                )
                ProfileTimelineTab.PostsAndReplies -> NoteTimeline(
                    state = postsAndRepliesState,
                    ownPubkey = ownPubkey,
                    onUserClick = onUserClick,
                    onLoadMore = postsAndRepliesViewModel::loadMore,
                    onLike = postsAndRepliesViewModel::react,
                    onUnlike = postsAndRepliesViewModel::unreact,
                    onEmojiReact = postsAndRepliesViewModel::reactWithEmoji,
                    onEmojiUnreact = postsAndRepliesViewModel::unreactWithEmoji,
                    onDelete = postsAndRepliesViewModel::deleteEvent,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    onReply = onReply,
                    onOpenReplies = onOpenReplies,
                    onOpenLikes = onOpenLikes,
                    onOpenReposts = onOpenReposts,
                    onRepost = postsAndRepliesViewModel::repost,
                    onUnrepost = postsAndRepliesViewModel::unrepost,
                    onReport = postsAndRepliesViewModel::reportEvent,
                    listState = profileListState,
                    header = profileHeader,
                )
            }
        }
    }
}

@Composable
private fun GeneralStatusEditDialog(
    currentStatus: String,
    isPublishing: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var status by remember(currentStatus) { mutableStateOf(currentStatus) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ステータスを編集") },
        text = {
            androidx.compose.foundation.layout.Column {
                OutlinedTextField(
                    value = status,
                    onValueChange = { status = it },
                    label = { Text("general") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                errorMessage?.let {
                    Text(
                        text = it,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isPublishing,
                onClick = { onSave(status) },
            ) {
                Text(if (isPublishing) "保存中" else "保存")
            }
        },
    )
}
