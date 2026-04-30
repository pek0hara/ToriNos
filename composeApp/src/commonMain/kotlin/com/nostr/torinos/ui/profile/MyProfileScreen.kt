package com.nostr.torinos.ui.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nostr.torinos.ui.components.LinkedText
import com.nostr.torinos.ui.components.noteListItems
import com.nostr.torinos.ui.feed.FeedViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyProfileScreen(
    ownPubkey: String,
    onOpenFollowing: () -> Unit = {},
    onOpenFollowers: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onUserClick: (String) -> Unit = {},
    viewModel: MyProfileViewModel = viewModel(
        factory = viewModelFactory { initializer { MyProfileViewModel(ownPubkey) } },
    ),
    feedViewModel: FeedViewModel = viewModel(key = "feed") { FeedViewModel(ownPubkey) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val feedState by feedViewModel.state.collectAsStateWithLifecycle()
    var showEditSheet by remember { mutableStateOf(false) }
    val editProfileViewModel = viewModel<EditProfileViewModel>(
        key = "editProfile",
        factory = viewModelFactory { initializer { EditProfileViewModel() } },
    )

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
        feedViewModel.injectProfile(ownPubkey, profile)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("プロフィール") },
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

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AvatarCircle(
                        pubkey = ownPubkey,
                        name = state.profile?.bestName,
                        pictureUrl = state.profile?.picture,
                        size = 64,
                    )
                    Text(
                        text = state.profile?.bestName
                            ?: (ownPubkey.take(8) + "…" + ownPubkey.takeLast(8)),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    if (!state.profile?.about.isNullOrBlank()) {
                        LinkedText(
                            text = state.profile!!.about!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!state.profile?.nip05.isNullOrBlank()) {
                        Text(
                            text = state.profile!!.nip05!!,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                HorizontalDivider()
            }

            item {
                Row(modifier = Modifier.fillMaxWidth()) {
                    UserStatCell(
                        label = "フォロー",
                        count = state.followingCount,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onOpenFollowing() },
                    )
                    UserStatCell(
                        label = "フォロワー",
                        count = state.followersCount,
                        isLoading = state.isFollowersLoading,
                        onFetch = viewModel::fetchFollowers,
                        fetched = state.followersLoaded,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onOpenFollowers() },
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
            )
        }
    }
}
