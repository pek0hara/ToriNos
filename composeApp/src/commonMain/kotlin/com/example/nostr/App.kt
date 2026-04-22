package com.example.nostr

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.nostr.ui.channel.ChannelListScreen
import com.example.nostr.ui.channel.ChannelScreen
import com.example.nostr.ui.feed.FeedScreen
import com.example.nostr.ui.profile.UserProfileScreen
import com.example.nostr.ui.relay.RelaySettingsScreen
import com.example.nostr.ui.search.SearchScreen
import com.example.nostr.ui.theme.NostrTheme

@Composable
fun App() {
    NostrTheme {
        val nav = rememberNavController()
        val backStackEntry by nav.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route

        Column(modifier = Modifier.fillMaxSize()) {
            NavHost(
                navController = nav,
                startDestination = "feed",
                modifier = Modifier.weight(1f),
            ) {
                composable("feed") {
                    FeedScreen(
                        onOpenSettings = { nav.navigate("relays") },
                        onOpenSearch = { nav.navigate("search") },
                        onUserClick = { pubkey -> nav.navigate("profile/$pubkey") },
                    )
                }
                composable("channels") {
                    ChannelListScreen(
                        onChannelClick = { id -> nav.navigate("channel/$id") },
                    )
                }
                composable("channel/{channelId}") { backStack ->
                    val channelId = backStack.arguments?.getString("channelId") ?: return@composable
                    ChannelScreen(
                        channelId = channelId,
                        onBack = { nav.popBackStack() },
                        onUserClick = { pubkey -> nav.navigate("profile/$pubkey") },
                    )
                }
                composable("relays") {
                    RelaySettingsScreen(onBack = { nav.popBackStack() })
                }
                composable("search") {
                    SearchScreen(
                        onBack = { nav.popBackStack() },
                        onUserClick = { pubkey -> nav.navigate("profile/$pubkey") },
                    )
                }
                composable("profile/{pubkey}") { backStack ->
                    val pubkey = backStack.arguments?.getString("pubkey") ?: return@composable
                    UserProfileScreen(
                        pubkey = pubkey,
                        onBack = { nav.popBackStack() },
                    )
                }
            }

            if (currentRoute == "feed" || currentRoute == "channels") {
                NavigationBar {
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Home, contentDescription = null) },
                        label = { Text("フィード") },
                        selected = currentRoute == "feed",
                        onClick = {
                            nav.navigate("feed") {
                                popUpTo("feed") { inclusive = false }
                                launchSingleTop = true
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
        }
    }
}
