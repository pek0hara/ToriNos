package com.nostr.torinos

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlin.coroutines.cancellation.CancellationException
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.nostr.torinos.crypto.isWriteSupported
import com.nostr.torinos.crypto.loadPublicKey
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.toProfile
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.ui.channel.ChannelListScreen
import com.nostr.torinos.ui.channel.ChannelScreen
import com.nostr.torinos.ui.feed.FeedTab
import com.nostr.torinos.ui.feed.FeedScreen
import com.nostr.torinos.ui.settings.MuteListScreen
import com.nostr.torinos.ui.settings.NgWordScreen
import com.nostr.torinos.ui.post.PostSheet
import com.nostr.torinos.ui.profile.FollowListMode
import com.nostr.torinos.ui.profile.FollowListScreen
import com.nostr.torinos.ui.profile.MyProfileScreen
import com.nostr.torinos.ui.profile.UserProfileScreen
import com.nostr.torinos.ui.settings.SettingsScreen
import com.nostr.torinos.ui.setup.KeySetupScreen
import com.nostr.torinos.ui.theme.NostrTheme
import com.nostr.torinos.ui.thread.ThreadScreen
import com.nostr.torinos.util.appLog
import com.nostr.torinos.util.loggingExceptionHandler
import com.nostr.torinos.util.logException
import kotlinx.serialization.Serializable

// 型安全なルート定義（パラメータ付き画面）
@Serializable data class ChannelRoute(val channelId: String)
@Serializable data class ProfileRoute(val pubkey: String)
@Serializable data class FollowingRoute(val pubkey: String)
@Serializable data class FollowersRoute(val pubkey: String)
@Serializable data class ThreadRoute(
    val eventId: String,
    val initialTab: String = "replies",
    val source: String = "",
)

private const val ThreadSourceChannel = "channel"

private enum class PendingKeyAction {
    NewPost,
    Reply,
    Profile,
}

@Composable
fun App() {
    NostrTheme {
        val nav = rememberNavController()
        val backStackEntry by nav.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route
        var showPostSheet by remember { mutableStateOf(false) }
        var replyToId by remember { mutableStateOf<String?>(null) }
        var replyToPubkey by remember { mutableStateOf<String?>(null) }
        var replyToPreview by remember { mutableStateOf<String?>(null) }
        var showKeySetup by remember { mutableStateOf(false) }
        var pendingKeyAction by remember { mutableStateOf<PendingKeyAction?>(null) }
        val scope = rememberCoroutineScope()
        val uiExceptionHandler = remember {
            loggingExceptionHandler("App", "Uncaught UI coroutine exception")
        }

        var ownPubkey by remember { mutableStateOf<String?>(null) }
        var ownProfile by remember { mutableStateOf<NostrProfile?>(null) }
        var feedScrollToTopRequest by remember { mutableStateOf(0) }
        var currentFeedTab by remember { mutableStateOf(FeedTab.Following) }
        var feedScrollToTopTargetTab by remember { mutableStateOf(FeedTab.Following) }
        val followingFeedListState = remember { LazyListState() }
        val allPostsFeedListState = remember { LazyListState() }

        // 起動時に保存済み秘密鍵から公開鍵を読み込む
        LaunchedEffect(Unit) {
            appLog("[App] startup: loading saved public key")
            try {
                ownPubkey = loadPublicKey()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logException("App", e, "Failed to load public key on startup")
            }
        }

        // ownPubkey が確定したらプロフィールを購読（ログイン後の再実行にも対応）
        LaunchedEffect(ownPubkey) {
            val pk = ownPubkey ?: return@LaunchedEffect
            ownProfile = null
            try {
                NostrRepository.subscribe(
                    "app-self-profile",
                    NostrFilter(kinds = listOf(0), authors = listOf(pk), limit = 1),
                )
                NostrRepository.events("app-self-profile").collect { event ->
                    if (event.kind == 0) {
                        ownProfile = event.toProfile()
                        NostrRepository.close("app-self-profile")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logException("App", e, "Failed to load own profile")
            }
        }

        fun runWithPrivateKey(
            missingAction: PendingKeyAction,
            onAvailable: (pubkeyHex: String) -> Unit,
        ) {
            scope.launch(uiExceptionHandler) {
                val pubkey = ownPubkey ?: try {
                    loadPublicKey()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logException("App", e, "Failed to load public key before protected action")
                    null
                }

                if (pubkey != null) {
                    ownPubkey = pubkey
                    onAvailable(pubkey)
                } else {
                    pendingKeyAction = missingAction
                    showKeySetup = true
                }
            }
        }

        fun clearLocalAccountState() {
            ownPubkey = null
            ownProfile = null
            showPostSheet = false
            showKeySetup = false
            pendingKeyAction = null
            replyToId = null
            replyToPubkey = null
            replyToPreview = null
            nav.navigate("feed") {
                popUpTo("feed") { inclusive = true }
                launchSingleTop = true
            }
        }

        fun handleAccountChanged(pubkey: String?) {
            if (pubkey == null) {
                clearLocalAccountState()
                return
            }
            ownPubkey = pubkey
            ownProfile = null
            showPostSheet = false
            showKeySetup = false
            pendingKeyAction = null
            replyToId = null
            replyToPubkey = null
            replyToPreview = null
            nav.navigate("feed") {
                popUpTo("feed") { inclusive = true }
                launchSingleTop = true
            }
        }

        val bottomBarRoutes = setOf("feed", "channels")
        val routeName = currentRoute?.substringBefore("/")
        val isChannelRoute = routeName?.endsWith("ChannelRoute") == true
        val threadRoute = if (routeName?.endsWith("ThreadRoute") == true) {
            runCatching { backStackEntry?.toRoute<ThreadRoute>() }.getOrNull()
        } else {
            null
        }
        val isChannelThreadRoute = threadRoute?.source == ThreadSourceChannel
        val isProfileRoute = currentRoute == "myprofile" ||
            routeName?.endsWith("ProfileRoute") == true
        val hasBottomBar = currentRoute in bottomBarRoutes || isChannelRoute || isChannelThreadRoute || isProfileRoute

        Scaffold(
            contentWindowInsets = WindowInsets(0),
            floatingActionButton = {
                if (isWriteSupported && currentRoute == "feed") {
                    FloatingActionButton(onClick = {
                        runWithPrivateKey(PendingKeyAction.NewPost) {
                            replyToId = null
                            replyToPubkey = null
                            replyToPreview = null
                            showPostSheet = true
                        }
                    }) {
                        Icon(Icons.Default.Create, contentDescription = "ポスト")
                    }
                }
            },
            bottomBar = {
                if (hasBottomBar) {
                    NavigationBar {
                        NavigationBarItem(
                            icon = { Icon(Icons.Default.Home, contentDescription = null) },
                            label = { Text("フィード") },
                            selected = currentRoute == "feed",
                            onClick = {
                                if (currentRoute == "feed") {
                                    feedScrollToTopTargetTab = currentFeedTab
                                    feedScrollToTopRequest++
                                } else {
                                    nav.navigate("feed") {
                                        popUpTo("feed") { saveState = true; inclusive = false }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                        )
                        NavigationBarItem(
                            icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                            label = { Text("チャンネル") },
                            selected = currentRoute == "channels" || isChannelRoute || isChannelThreadRoute,
                            onClick = {
                                nav.navigate("channels") {
                                    popUpTo("feed") { saveState = true; inclusive = false }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                        )
                    }
                }
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = if (hasBottomBar) padding.calculateBottomPadding() else 0.dp),
            ) {
                NavHost(
                    navController = nav,
                    startDestination = "feed",
                    modifier = Modifier.weight(1f),
                ) {
                    composable("feed") {
                        FeedScreen(
                            onOpenSettings = { nav.navigate("settings") },
                            onUserClick = { pubkey -> nav.navigate(ProfileRoute(pubkey)) },
                            onOpenProfile = {
                                runWithPrivateKey(PendingKeyAction.Profile) {
                                    nav.navigate("myprofile") { launchSingleTop = true }
                                }
                            },
                            onReply = { eventId, authorPk, preview ->
                                replyToId = eventId
                                replyToPubkey = authorPk
                                replyToPreview = preview
                                runWithPrivateKey(PendingKeyAction.Reply) {
                                    showPostSheet = true
                                }
                            },
                            onOpenReplies = { eventId -> nav.navigate(ThreadRoute(eventId)) },
                            onOpenLikes = { eventId -> nav.navigate(ThreadRoute(eventId, "likes")) },
                            onOpenReposts = { eventId -> nav.navigate(ThreadRoute(eventId, "reposts")) },
                            ownPubkey = ownPubkey,
                            ownProfile = ownProfile,
                            scrollToTopRequest = feedScrollToTopRequest,
                            scrollToTopTargetTab = feedScrollToTopTargetTab,
                            onCurrentFeedTabChanged = { currentFeedTab = it },
                            followingListState = followingFeedListState,
                            allPostsListState = allPostsFeedListState,
                        )
                    }
                    composable("channels") {
                        ChannelListScreen(
                            onChannelClick = { id -> nav.navigate(ChannelRoute(id)) },
                        )
                    }
                    composable<ChannelRoute> { backStack ->
                        val route = backStack.toRoute<ChannelRoute>()
                        ChannelScreen(
                            channelId = route.channelId,
                            onBack = { nav.popBackStack() },
                            onUserClick = { pubkey -> nav.navigate(ProfileRoute(pubkey)) },
                            onOpenThread = { eventId -> nav.navigate(ThreadRoute(eventId, source = ThreadSourceChannel)) },
                            onOpenLikes = { eventId ->
                                nav.navigate(ThreadRoute(eventId, "likes", ThreadSourceChannel))
                            },
                            onOpenReposts = { eventId ->
                                nav.navigate(ThreadRoute(eventId, "reposts", ThreadSourceChannel))
                            },
                            ownPubkey = ownPubkey,
                        )
                    }
                    composable<ThreadRoute> { backStack ->
                        val route = backStack.toRoute<ThreadRoute>()
                        ThreadScreen(
                            eventId = route.eventId,
                            initialTab = route.initialTab,
                            onBack = { nav.popBackStack() },
                            onUserClick = { pubkey -> nav.navigate(ProfileRoute(pubkey)) },
                            onReply = { eventId, authorPk, preview ->
                                replyToId = eventId
                                replyToPubkey = authorPk
                                replyToPreview = preview
                                runWithPrivateKey(PendingKeyAction.Reply) {
                                    showPostSheet = true
                                }
                            },
                            onOpenThread = { eventId -> nav.navigate(ThreadRoute(eventId)) },
                            onOpenLikes = { eventId -> nav.navigate(ThreadRoute(eventId, "likes")) },
                            onOpenReposts = { eventId -> nav.navigate(ThreadRoute(eventId, "reposts")) },
                            ownPubkey = ownPubkey,
                        )
                    }
                    composable("myprofile") {
                        val pubkey = ownPubkey ?: return@composable
                        MyProfileScreen(
                            ownPubkey = pubkey,
                            onBack = { nav.popBackStack() },
                            onOpenFollowing = { nav.navigate(FollowingRoute(pubkey)) },
                            onOpenFollowers = { nav.navigate(FollowersRoute(pubkey)) },
                            onOpenSettings = { nav.navigate("settings") },
                            onUserClick = { pk -> nav.navigate(ProfileRoute(pk)) },
                            onReply = { eventId, authorPk, preview ->
                                replyToId = eventId
                                replyToPubkey = authorPk
                                replyToPreview = preview
                                runWithPrivateKey(PendingKeyAction.Reply) {
                                    showPostSheet = true
                                }
                            },
                            onOpenReplies = { eventId -> nav.navigate(ThreadRoute(eventId)) },
                            onOpenLikes = { eventId -> nav.navigate(ThreadRoute(eventId, "likes")) },
                            onOpenReposts = { eventId -> nav.navigate(ThreadRoute(eventId, "reposts")) },
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            ownPubkey = ownPubkey,
                            onBack = { nav.popBackStack() },
                            onAccountChanged = ::handleAccountChanged,
                            onAddAccountClick = {
                                pendingKeyAction = null
                                showKeySetup = true
                            },
                            onMuteListClick = { nav.navigate("mute-list") },
                            onNgWordClick = { nav.navigate("ng-words") },
                        )
                    }
                    composable("mute-list") {
                        MuteListScreen(onBack = { nav.popBackStack() })
                    }
                    composable("ng-words") {
                        NgWordScreen(onBack = { nav.popBackStack() })
                    }
                    composable<FollowingRoute> { backStack ->
                        val route = backStack.toRoute<FollowingRoute>()
                        FollowListScreen(
                            mode = FollowListMode.FOLLOWING,
                            ownPubkey = route.pubkey,
                            onBack = { nav.popBackStack() },
                            onUserClick = { pk -> nav.navigate(ProfileRoute(pk)) },
                        )
                    }
                    composable<FollowersRoute> { backStack ->
                        val route = backStack.toRoute<FollowersRoute>()
                        FollowListScreen(
                            mode = FollowListMode.FOLLOWERS,
                            ownPubkey = route.pubkey,
                            onBack = { nav.popBackStack() },
                            onUserClick = { pk -> nav.navigate(ProfileRoute(pk)) },
                        )
                    }
                    composable<ProfileRoute> { backStack ->
                        val route = backStack.toRoute<ProfileRoute>()
                        UserProfileScreen(
                            pubkey = route.pubkey,
                            onBack = { nav.popBackStack() },
                            isOwnProfile = false,
                            ownPubkey = ownPubkey,
                            onOpenFollowing = { nav.navigate(FollowingRoute(route.pubkey)) },
                            onOpenFollowers = { nav.navigate(FollowersRoute(route.pubkey)) },
                            onUserClick = { pk -> nav.navigate(ProfileRoute(pk)) },
                            onReply = { eventId, authorPk, preview ->
                                replyToId = eventId
                                replyToPubkey = authorPk
                                replyToPreview = preview
                                runWithPrivateKey(PendingKeyAction.Reply) {
                                    showPostSheet = true
                                }
                            },
                            onOpenReplies = { eventId -> nav.navigate(ThreadRoute(eventId)) },
                            onOpenLikes = { eventId -> nav.navigate(ThreadRoute(eventId, "likes")) },
                            onOpenReposts = { eventId -> nav.navigate(ThreadRoute(eventId, "reposts")) },
                        )
                    }
                }
            }
        }

        if (showPostSheet) {
            PostSheet(
                onDismiss = {
                    showPostSheet = false
                    replyToId = null
                    replyToPubkey = null
                    replyToPreview = null
                },
                replyToId = replyToId,
                replyToPubkey = replyToPubkey,
                replyToPreview = replyToPreview,
            )
        }

        if (showKeySetup) {
            KeySetupScreen(
                onSetupComplete = { pubkeyHex ->
                    val action = pendingKeyAction
                    showKeySetup = false
                    pendingKeyAction = null
                    // 保存直後に導出済みの公開鍵を直接セット（Keychain 再読み込み不要）
                    ownPubkey = pubkeyHex
                    when (action) {
                        PendingKeyAction.NewPost -> {
                            replyToId = null
                            replyToPubkey = null
                            replyToPreview = null
                            showPostSheet = true
                        }
                        PendingKeyAction.Reply -> showPostSheet = true
                        PendingKeyAction.Profile -> nav.navigate("myprofile") { launchSingleTop = true }
                        null -> Unit
                    }
                },
                onDismiss = {
                    pendingKeyAction = null
                    replyToId = null
                    replyToPubkey = null
                    replyToPreview = null
                    showKeySetup = false
                },
            )
        }
    }
}
