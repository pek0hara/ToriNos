package com.nostr.torinos

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.nostr.torinos.account.AccountSessionHost
import com.nostr.torinos.account.AccountSessionState
import com.nostr.torinos.account.AccountSessions
import com.nostr.torinos.network.LocalSettingsStorage
import com.nostr.torinos.ui.theme.NostrTheme
import com.nostr.torinos.util.loggingExceptionHandler
import com.nostr.torinos.util.logException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

internal const val AgeVerificationAccepted = "accepted_13_or_older"
internal const val AgeVerificationBlocked = "blocked_under_13"
private const val AgeVerificationKey = "age_verification_status_v1"

/** アプリ共通状態と、アカウントごとに破棄される画面ツリーの境界。 */
@Composable
fun App() {
    NostrTheme {
        val accountSessionState by AccountSessions.manager.state.collectAsState()
        val accountTransitionError by AccountSessions.manager.transitionError.collectAsState()
        val appScope = rememberCoroutineScope()
        val appExceptionHandler = remember {
            loggingExceptionHandler("App", "Uncaught app coroutine exception")
        }
        var ageVerificationStatus by remember { mutableStateOf<String?>(null) }
        var isAgeVerificationLoaded by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            AccountSessions.manager.initialize()
            try {
                ageVerificationStatus = LocalSettingsStorage.getString(AgeVerificationKey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logException("App", e, "Failed to load age verification status")
            } finally {
                isAgeVerificationLoaded = true
            }
        }

        fun updateAgeVerification(value: String?) {
            ageVerificationStatus = value
            appScope.launch(appExceptionHandler) {
                runCatching { LocalSettingsStorage.putString(AgeVerificationKey, value) }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        logException("App", error, "Failed to save age verification status")
                    }
            }
        }

        AppSessionHost(
            state = accountSessionState,
            ageVerificationStatus = ageVerificationStatus,
            isAgeVerificationLoaded = isAgeVerificationLoaded,
            onAgeVerificationChanged = ::updateAgeVerification,
        )

        accountTransitionError?.let { message ->
            AlertDialog(
                onDismissRequest = AccountSessions.manager::consumeTransitionError,
                text = { Text(message) },
                confirmButton = {
                    TextButton(onClick = AccountSessions.manager::consumeTransitionError) {
                        Text("OK")
                    }
                },
            )
        }
    }
}

/** Loading/Switching とセッション所有 UI の切り替えだけを担当する。 */
@Composable
private fun AppSessionHost(
    state: AccountSessionState,
    ageVerificationStatus: String?,
    isAgeVerificationLoaded: Boolean,
    onAgeVerificationChanged: (String?) -> Unit,
) {
    when (state) {
        AccountSessionState.Loading,
        is AccountSessionState.Switching,
        -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        is AccountSessionState.Active -> AccountSessionHost(
            sessionId = state.session.sessionId,
            session = state.session,
        ) {
            AppSessionCoordinator(
                accountSession = state.session,
                ageVerificationStatus = ageVerificationStatus,
                isAgeVerificationLoaded = isAgeVerificationLoaded,
                onAgeVerificationChanged = onAgeVerificationChanged,
            )
        }

        is AccountSessionState.Anonymous -> AccountSessionHost(
            sessionId = state.sessionId,
            session = null,
        ) {
            AppSessionCoordinator(
                accountSession = null,
                ageVerificationStatus = ageVerificationStatus,
                isAgeVerificationLoaded = isAgeVerificationLoaded,
                onAgeVerificationChanged = onAgeVerificationChanged,
            )
        }
    }
}
