package com.nostr.torinos

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import com.nostr.torinos.ui.feed.FeedScreen
import com.nostr.torinos.ui.post.PostSheet
import com.nostr.torinos.ui.profile.FollowListMode
import com.nostr.torinos.ui.profile.FollowListScreen
import com.nostr.torinos.ui.profile.MyProfileScreen
import com.nostr.torinos.ui.profile.UserProfileScreen
import com.nostr.torinos.ui.search.SearchScreen
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
@Serializable data class ThreadRoute(val eventId: String, val initialTab: String = "replies")

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
        var showKeySetup by remember { mutableStateOf(false) }
        var pendingKeyAction by remember { mutableStateOf<PendingKeyAction?>(null) }
        val scope = rememberCoroutineScope()
        val uiExceptionHandler = remember {
            loggingExceptionHandler("App", "Uncaught UI coroutine exception")
        }

        var ownPubkey by remember { mutableStateOf<String?>(null) }
        var ownProfile by remember { mutableStateOf<NostrProfile?>(null) }
        var feedScrollToTopRequest by remember { mutableStateOf(0) }

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
            nav.navigate("feed") {
                popUpTo("feed") { inclusive = true }
                launchSingleTop = true
            }
        }

        val bottomBarRoutes = setOf("feed", "channels")

        Scaffold(
            floatingActionButton = {
                if (isWriteSupported && currentRoute == "feed") {
                    FloatingActionButton(onClick = {
                        runWithPrivateKey(PendingKeyAction.NewPost) {
                            replyToId = null
                            replyToPubkey = null
                            showPostSheet = true
                        }
                    }) {
                        Icon(Icons.Default.Create, contentDescription = "投稿")
                    }
                }
            },
            bottomBar = {
                if (currentRoute in bottomBarRoutes) {
                    NavigationBar {
                        NavigationBarItem(
                            icon = { Icon(Icons.Default.Home, contentDescription = null) },
                            label = { Text("フィード") },
                            selected = currentRoute == "feed",
                            onClick = {
                                if (currentRoute == "feed") {
                                    feedScrollToTopRequest++
                                } else {
                                    nav.navigate("feed") {
                                        popUpTo("feed") { inclusive = false }
                                        launchSingleTop = true
                                    }
                                }
                            },
                        )
                        NavigationBarItem(
                            icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                            label = { Text("チャンネル") },
                            selected = currentRoute == "channels",
                            onClick = {
                                nav.navigate("channels") {
                                    popUpTo("feed") { inclusive = false }
                                    launchSingleTop = true
                                }
                            },
                        )
                    }
                }
            },
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
                NavHost(
                    navController = nav,
                    startDestination = "feed",
                    modifier = Modifier.weight(1f),
                ) {
                    composable("feed") {
                        FeedScreen(
                            onOpenSettings = { nav.navigate("settings") },
                            onOpenSearch = { nav.navigate("search") },
                            onUserClick = { pubkey -> nav.navigate(ProfileRoute(pubkey)) },
                            onOpenProfile = {
                                runWithPrivateKey(PendingKeyAction.Profile) {
                                    nav.navigate("myprofile") { launchSingleTop = true }
                                }
                            },
                            onReply = { eventId, authorPk ->
                                replyToId = eventId
                                replyToPubkey = authorPk
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
                        )
                    }
                    composable("search") {
                        SearchScreen(
                            onBack = { nav.popBackStack() },
                            onUserClick = { pubkey -> nav.navigate(ProfileRoute(pubkey)) },
                        )
                    }
                    composable<ThreadRoute> { backStack ->
                        val route = backStack.toRoute<ThreadRoute>()
                        ThreadScreen(
                            eventId = route.eventId,
                            initialTab = route.initialTab,
                            onBack = { nav.popBackStack() },
                            onUserClick = { pubkey -> nav.navigate(ProfileRoute(pubkey)) },
                            onReply = { eventId, authorPk ->
                                replyToId = eventId
                                replyToPubkey = authorPk
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
                            onReply = { eventId, authorPk ->
                                replyToId = eventId
                                replyToPubkey = authorPk
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
                            onAccountCleared = ::clearLocalAccountState,
                        )
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
                            onReply = { eventId, authorPk ->
                                replyToId = eventId
                                replyToPubkey = authorPk
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
                },
                replyToId = replyToId,
                replyToPubkey = replyToPubkey,
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
                    showKeySetup = false
                },
            )
        }
    }
}
