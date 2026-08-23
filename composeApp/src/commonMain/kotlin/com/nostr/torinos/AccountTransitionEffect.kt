package com.nostr.torinos

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.nostr.torinos.account.AccountSession
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.network.ProfileFetchPolicy
import com.nostr.torinos.network.ProfileRepository
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.util.logException
import kotlin.coroutines.cancellation.CancellationException

/**
 * セッション開始時のアカウント依存処理を一か所に集約する。
 *
 * アカウント変更時のローカル UI state は AccountSessionHost がツリーごと破棄するため、
 * この Effect では新しいセッションの初期化だけを行う。
 */
@Composable
internal fun AccountTransitionEffect(
    session: AccountSession?,
    onOwnProfileChanged: (NostrProfile?) -> Unit,
) {
    val pubkey = session?.pubkey

    LaunchedEffect(pubkey) {
        if (pubkey == null) {
            onOwnProfileChanged(null)
            return@LaunchedEffect
        }
        try {
            onOwnProfileChanged(ProfileRepository.getCached(pubkey))
            ProfileRepository.ensureProfiles(
                setOf(pubkey),
                ProfileFetchPolicy.CacheFirst(15 * 60 * 1_000L),
            )
            ProfileRepository.observe(pubkey).collect(onOwnProfileChanged)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logException("App", e, "Failed to load own profile")
        }
    }

    LaunchedEffect(session?.sessionId) {
        RelayStore.activateAccount(pubkey)
        val activeSession = session ?: return@LaunchedEffect
        try {
            activeSession.relayListSynchronizer.syncFromRelays()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logException("App", e, "Failed to synchronize relay list")
        }
        activeSession.followRepository.refresh()
    }
}
