package com.nostr.torinos

import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal sealed interface ProfileDrawerDestination {
    val stateKey: String

    data class Profile(val pubkey: String) : ProfileDrawerDestination {
        override val stateKey: String = "profile-$pubkey"
    }

    data class Following(val pubkey: String) : ProfileDrawerDestination {
        override val stateKey: String = "following-$pubkey"
    }

    data class Followers(val pubkey: String) : ProfileDrawerDestination {
        override val stateKey: String = "followers-$pubkey"
    }

    data class Thread(
        val eventId: String,
        val initialTab: String = "auto",
        val channelId: String? = null,
    ) : ProfileDrawerDestination {
        override val stateKey: String = "thread-$eventId-${channelId.orEmpty()}-$initialTab"
    }
}

/** プロフィール／通知ドロワー間の排他的な遷移を管理する。 */
internal class DrawerCoordinator(
    val notificationsState: DrawerState,
    val profileState: DrawerState,
    private val scope: CoroutineScope,
) {
    var profileDestination by mutableStateOf<ProfileDrawerDestination?>(null)
        private set
    var isProfileContentReady by mutableStateOf(false)
        private set
    var notificationsScrollToTopRequest by mutableStateOf(0)
        private set
    var profileNavigationSessionId by mutableStateOf(0)
        private set

    private var hasProfileOpened = false
    private val profileHistory = mutableListOf<ProfileDrawerDestination>()
    private val transitionMutex = Mutex()

    fun openProfile(pubkey: String) {
        scope.launch {
            transitionMutex.withLock {
                val currentDestination = profileDestination
                val isProfileActive = currentDestination != null &&
                    (profileState.currentValue == DrawerValue.Open ||
                        profileState.targetValue == DrawerValue.Open)
                if (
                    currentDestination == ProfileDrawerDestination.Profile(pubkey) &&
                    isProfileActive
                ) {
                    // 開くアニメーションが中断されていても、同じプロフィールの再要求で
                    // ローディング表示に留まらないよう内容を表示可能に戻す。
                    isProfileContentReady = true
                    return@withLock
                }

                if (isProfileActive) {
                    profileHistory.add(checkNotNull(currentDestination))
                    profileDestination = ProfileDrawerDestination.Profile(pubkey)
                    isProfileContentReady = true
                    return@withLock
                }

                profileHistory.clear()
                isProfileContentReady = false
                notificationsState.close()
                profileState.close()
                profileDestination = ProfileDrawerDestination.Profile(pubkey)
                profileNavigationSessionId++
                // DrawerState.open() はアニメーション完了まで suspend し、ジェスチャー等で
                // 中断され得るため、内容の表示開始をその完了に依存させない。
                isProfileContentReady = true
                profileState.open()
            }
        }
    }

    fun openFollowing(pubkey: String) {
        openFollowList(
            source = ProfileDrawerDestination.Profile(pubkey),
            destination = ProfileDrawerDestination.Following(pubkey),
        )
    }

    fun openFollowers(pubkey: String) {
        openFollowList(
            source = ProfileDrawerDestination.Profile(pubkey),
            destination = ProfileDrawerDestination.Followers(pubkey),
        )
    }

    private fun openFollowList(
        source: ProfileDrawerDestination.Profile,
        destination: ProfileDrawerDestination,
    ) {
        scope.launch {
            transitionMutex.withLock {
                if (profileDestination != source) return@withLock
                profileHistory.add(source)
                profileDestination = destination
                isProfileContentReady = true
            }
        }
    }

    fun openThread(
        source: ProfileDrawerDestination,
        eventId: String,
        initialTab: String = "auto",
        channelId: String? = null,
    ) {
        scope.launch {
            transitionMutex.withLock {
                if (profileDestination != source) return@withLock
                profileHistory.add(source)
                profileDestination = ProfileDrawerDestination.Thread(
                    eventId = eventId,
                    initialTab = initialTab,
                    channelId = channelId,
                )
                isProfileContentReady = true
            }
        }
    }

    fun navigateBackOrCloseProfile() {
        scope.launch {
            transitionMutex.withLock {
                if (profileHistory.isNotEmpty()) {
                    profileDestination = profileHistory.removeAt(profileHistory.lastIndex)
                    isProfileContentReady = true
                } else {
                    profileState.close()
                }
            }
        }
    }

    fun openNotifications() {
        scope.launch {
            transitionMutex.withLock {
                profileState.close()
                notificationsState.open()
            }
        }
    }

    fun closeProfileAndThen(action: () -> Unit) {
        scope.launch {
            transitionMutex.withLock {
                profileState.close()
                action()
            }
        }
    }

    fun onProfileStateChanged() {
        scope.launch {
            transitionMutex.withLock {
                when {
                    profileState.currentValue == DrawerValue.Open -> hasProfileOpened = true
                    profileState.currentValue == DrawerValue.Closed &&
                        profileState.targetValue == DrawerValue.Closed &&
                        hasProfileOpened -> {
                        hasProfileOpened = false
                        isProfileContentReady = false
                        profileDestination = null
                        profileHistory.clear()
                    }
                }
            }
        }
    }

    fun onNotificationsOpened() {
        notificationsScrollToTopRequest++
    }
}

@Composable
internal fun rememberDrawerCoordinator(scope: CoroutineScope): DrawerCoordinator {
    val notificationsState = rememberDrawerState(DrawerValue.Closed)
    val profileState = rememberDrawerState(DrawerValue.Closed)
    return remember(notificationsState, profileState, scope) {
        DrawerCoordinator(notificationsState, profileState, scope)
    }
}
