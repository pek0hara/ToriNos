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

/** プロフィール／通知ドロワー間の排他的な遷移を管理する。 */
internal class DrawerCoordinator(
    val notificationsState: DrawerState,
    val profileState: DrawerState,
    private val scope: CoroutineScope,
) {
    var profilePubkey by mutableStateOf<String?>(null)
        private set
    var isProfileContentReady by mutableStateOf(false)
        private set
    var notificationsScrollToTopRequest by mutableStateOf(0)
        private set
    var profileNavigationSessionId by mutableStateOf(0)
        private set

    private var hasProfileOpened = false
    private val profileHistory = mutableListOf<String>()
    private val transitionMutex = Mutex()

    fun openProfile(pubkey: String) {
        scope.launch {
            transitionMutex.withLock {
                val isProfileActive = profilePubkey != null &&
                    (profileState.currentValue == DrawerValue.Open ||
                        profileState.targetValue == DrawerValue.Open)
                if (
                    profilePubkey == pubkey &&
                    isProfileActive
                ) {
                    return@withLock
                }

                if (isProfileActive) {
                    profilePubkey?.let(profileHistory::add)
                    profilePubkey = pubkey
                    isProfileContentReady = true
                    return@withLock
                }

                profileHistory.clear()
                isProfileContentReady = false
                notificationsState.close()
                profileState.close()
                profilePubkey = pubkey
                profileNavigationSessionId++
                profileState.open()
                isProfileContentReady = true
            }
        }
    }

    fun navigateBackOrCloseProfile() {
        scope.launch {
            transitionMutex.withLock {
                if (profileHistory.isNotEmpty()) {
                    profilePubkey = profileHistory.removeAt(profileHistory.lastIndex)
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
            profileState.close()
            action()
        }
    }

    fun onProfileStateChanged() {
        when {
            profileState.currentValue == DrawerValue.Open -> hasProfileOpened = true
            profileState.currentValue == DrawerValue.Closed &&
                profileState.targetValue == DrawerValue.Closed &&
                hasProfileOpened -> {
                hasProfileOpened = false
                isProfileContentReady = false
                profilePubkey = null
                profileHistory.clear()
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
