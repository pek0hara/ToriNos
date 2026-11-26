package com.nostr.torinos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nostr.torinos.account.AccountSessionHost
import com.nostr.torinos.account.AccountSessionState
import com.nostr.torinos.chat.GroupInput
import com.nostr.torinos.chat.inviteFromFragment
import com.nostr.torinos.chat.parseGroupInput
import com.nostr.torinos.chat.pendingInvites
import com.nostr.torinos.chat.toriNosAndroidIntentLink
import com.nostr.torinos.chat.toriNosAppInviteLink
import com.nostr.torinos.chat.toriNosInviteLink
import com.nostr.torinos.ui.setup.PhoneInviteLanding
import kotlin.time.Clock
import com.nostr.torinos.account.AccountSessions
import com.nostr.torinos.badge.AppBadgeCachePersistence
import com.nostr.torinos.ui.components.SignerStatusBanner
import com.nostr.torinos.network.AppProfilePersistence
import com.nostr.torinos.network.DisplayPreferencesStore
import com.nostr.torinos.network.LocalSettingsStorage
import com.nostr.torinos.ui.theme.NostrTheme
import com.nostr.torinos.util.loggingExceptionHandler
import com.nostr.torinos.util.logException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

internal const val AgeVerificationAccepted = "accepted_13_or_older"
internal const val AgeVerificationBlocked = "blocked_under_13"
private const val AgeVerificationKey = "age_verification_status_v1"

/** Material 3 の Compact / Medium の境界。この幅以上で NavigationRail に切り替える。 */
private val WideLayoutMinWidth = 600.dp

/** アプリ共通状態と、アカウントごとに破棄される画面ツリーの境界。 */
@Composable
fun App() {
    val fontScale by DisplayPreferencesStore.fontScale.collectAsState()
    NostrTheme(fontScale = fontScale) {
        val accountSessionState by AccountSessions.manager.state.collectAsState()
        val accountTransitionError by AccountSessions.manager.transitionError.collectAsState()
        val appScope = rememberCoroutineScope()
        val appExceptionHandler = remember {
            loggingExceptionHandler("App", "Uncaught app coroutine exception")
        }
        var ageVerificationStatus by remember { mutableStateOf<String?>(null) }
        var isAgeVerificationLoaded by remember { mutableStateOf(false) }
        // 鍵設定の完了でセッションが作り直されても、フィードの＋からの新規投稿の再開要求を失わない。
        val pendingComposerRequests = remember { PendingComposerRequestHolder() }

        LaunchedEffect(accountTransitionError) {
            if (accountTransitionError != null) pendingComposerRequests.requestRegistration(null)
        }

        // スマホのブラウザで招待リンクを開いたときの入口（docs/buzz-invite-link-design.md）。
        var phoneInviteLink by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(Unit) {
            AppProfilePersistence.start()
            AppBadgeCachePersistence.start()
            AccountSessions.manager.initialize()
            receiveLaunchInvite(
                loggedIn = AccountSessions.manager.state.value is AccountSessionState.Active,
                showPhoneLanding = { phoneInviteLink = it },
            )
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

        CenteredContentColumn {
            AppSessionHost(
                state = accountSessionState,
                ageVerificationStatus = ageVerificationStatus,
                isAgeVerificationLoaded = isAgeVerificationLoaded,
                onAgeVerificationChanged = ::updateAgeVerification,
                pendingComposerRequests = pendingComposerRequests,
            )
        }

        phoneInviteLink?.let { link ->
            val invite = remember(link) { parseGroupInput(link) as? GroupInput.BuzzInvite }
            val phone = remember { currentPhoneBrowser() }
            if (invite == null || phone == null) {
                phoneInviteLink = null
            } else {
                PhoneInviteLanding(
                    host = invite.relayUrl.substringAfter("://").trimEnd('/'),
                    link = link,
                    appLink = when (phone) {
                        PhoneBrowser.Android -> toriNosAndroidIntentLink(invite.relayUrl, invite.code)
                        PhoneBrowser.Ios -> toriNosAppInviteLink(invite.relayUrl, invite.code)
                    },
                    phone = phone,
                    onContinueInBrowser = {
                        pendingInvites.offer(link, Clock.System.now().toEpochMilliseconds())
                        phoneInviteLink = null
                    },
                    onClose = { phoneInviteLink = null },
                )
            }
        }

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

/** 画面幅が [WideLayoutMinWidth] 以上（Medium / Expanded）かどうか。true のときはボトムバーの代わりに NavigationRail を使う。 */
internal val LocalIsWideLayout = compositionLocalOf { false }

/** 常に全幅を渡し、横長のときは [LocalIsWideLayout] を立てて、レールと本文の配置は [AppScaffold] に任せる。 */
@Composable
private fun CenteredContentColumn(content: @Composable () -> Unit) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        CompositionLocalProvider(
            LocalIsWideLayout provides (maxWidth >= WideLayoutMinWidth),
        ) {
            content()
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
    pendingComposerRequests: PendingComposerRequestHolder,
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
            Box(Modifier.fillMaxSize()) {
                AppSessionCoordinator(
                    accountSession = state.session,
                    ageVerificationStatus = ageVerificationStatus,
                    isAgeVerificationLoaded = isAgeVerificationLoaded,
                    onAgeVerificationChanged = onAgeVerificationChanged,
                    pendingComposerRequests = pendingComposerRequests,
                )
                // 解錠や署名器での承認を待っているときだけ、本文の上に重ねて出す。
                SignerStatusBanner(state.session, Modifier.align(Alignment.TopCenter))
            }
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
                pendingComposerRequests = pendingComposerRequests,
            )
        }
    }
}

/**
 * Web 版を開いた URL にあった招待を受け取る。スマホのブラウザで、まだログインしていなければアプリへ案内する
 * （スマホのブラウザで新しく鍵を作らせない）。それ以外は預けて、ログインの後に参加シートで確かめる。
 */
private fun receiveLaunchInvite(loggedIn: Boolean, showPhoneLanding: (String) -> Unit) {
    val fragment = takeLaunchInviteFragment() ?: return
    val invite = inviteFromFragment(fragment) ?: return
    val link = toriNosInviteLink(invite.relayUrl, invite.code)
    if (currentPhoneBrowser() != null && !loggedIn) {
        showPhoneLanding(link)
    } else {
        pendingInvites.offer(link, Clock.System.now().toEpochMilliseconds())
    }
}
