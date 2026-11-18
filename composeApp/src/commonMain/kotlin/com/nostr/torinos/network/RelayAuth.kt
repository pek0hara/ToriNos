package com.nostr.torinos.network

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.RelayMessage
import com.nostr.torinos.util.SynchronousLock
import com.nostr.torinos.util.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** NIP-42 の認証イベント（kind 22242）。 */
internal const val RELAY_AUTH_KIND = 22242

/** kind 22242 のタグ。content は空。 */
internal fun authEventTags(relayUrl: String, challenge: String): List<List<String>> =
    listOf(listOf("relay", relayUrl), listOf("challenge", challenge))

/** 接続ごとの NIP-42 の状態。[generation] は [NostrRelay.connected] の接続世代。 */
sealed interface RelayAuthState {
    val generation: Long

    /** チャレンジを受けたが、まだ認証していない（許可リストに無い、署名器が無い）。 */
    data class Challenged(override val generation: Long, val challenge: String) : RelayAuthState
    data class Authenticating(override val generation: Long, val challenge: String, val pubkey: String) : RelayAuthState
    data class Authenticated(override val generation: Long, val pubkey: String) : RelayAuthState
    data class Failed(override val generation: Long, val pubkey: String, val reason: String) : RelayAuthState
}

sealed interface RelayAuthResult {
    val relayUrl: String
    val generation: Long

    data class Succeeded(override val relayUrl: String, override val generation: Long) : RelayAuthResult
    data class Failed(override val relayUrl: String, override val generation: Long, val reason: String) : RelayAuthResult
}

/** 認証イベントへの署名。失敗は例外（`SignerException` など）で返す。 */
fun interface RelayAuthSigner {
    suspend fun sign(relayUrl: String, challenge: String): NostrEvent
}

/** 認証イベントを [generation] の接続で送り、OK を待つ。接続が変わっていた・応答が無いときは null。 */
internal fun interface RelayAuthSender {
    suspend fun send(relayUrl: String, generation: Long, event: NostrEvent): RelayMessage.Ok?
}

/**
 * NIP-42 の認証を、リレーと接続世代ごとに管理する。
 *
 * 認証するのは、署名器が登録されていて、かつ [install] の `allowed` が許すリレーだけ。それ以外のリレーには
 * 公開鍵を知らせない。チャレンジは覚えておき、後から許可されたとき（[onPolicyChanged]）に使う。
 */
internal class RelayAuthCoordinator(
    private val scope: CoroutineScope,
    private val sender: RelayAuthSender,
) {
    private class Account(val pubkey: String, val signer: RelayAuthSigner, val allowed: (String) -> Boolean)

    private val lock = SynchronousLock()
    private var account: Account? = null
    /** リレーごとの試行番号。切断や新しいチャレンジで進め、古い試行の結果を捨てる。 */
    private val attempts = mutableMapOf<String, Long>()
    private val _states = MutableStateFlow<Map<String, RelayAuthState>>(emptyMap())
    val states: StateFlow<Map<String, RelayAuthState>> = _states.asStateFlow()
    private val _results = MutableSharedFlow<RelayAuthResult>(extraBufferCapacity = 64)
    val results: SharedFlow<RelayAuthResult> = _results.asSharedFlow()

    /**
     * アカウントの署名器を登録する。前のアカウントで認証済み（または認証中）だったリレーを返す。
     * 呼び出し側はそれらの接続を張り直す（別の身元で読み続けないため）。
     */
    fun install(pubkey: String, signer: RelayAuthSigner, allowed: (String) -> Boolean): Set<String> {
        val (stale, toStart) = lock.withLock {
            val stale = dropAuthenticatedLocked(keepPubkey = pubkey)
            account = Account(pubkey, signer, allowed)
            val toStart = _states.value.mapNotNull { (url, state) ->
                (state as? RelayAuthState.Challenged)?.takeIf { allowed(url) }?.let { url to it }
            }
            stale to toStart
        }
        toStart.forEach { (url, state) -> start(url, state.generation, state.challenge) }
        return stale
    }

    /** 署名器を外す。認証済み（または認証中）だったリレーを返す。 */
    fun uninstall(): Set<String> = lock.withLock {
        account = null
        dropAuthenticatedLocked(keepPubkey = null)
    }

    fun canAuthenticate(relayUrl: String): Boolean = lock.withLock {
        account?.allowed?.invoke(relayUrl) == true
    }

    fun isAuthenticated(relayUrl: String, generation: Long): Boolean {
        val state = _states.value[relayUrl]
        return state is RelayAuthState.Authenticated && state.generation == generation
    }

    fun isFailed(relayUrl: String, generation: Long): Boolean {
        val state = _states.value[relayUrl]
        return state is RelayAuthState.Failed && state.generation == generation
    }

    fun onChallenge(relayUrl: String, generation: Long, challenge: String) {
        val shouldStart = lock.withLock {
            bumpLocked(relayUrl)
            update(relayUrl, RelayAuthState.Challenged(generation, challenge))
            account?.allowed?.invoke(relayUrl) == true
        }
        if (shouldStart) start(relayUrl, generation, challenge)
    }

    /** 許可リストが変わった。チャレンジを受けたまま認証していないリレーなら、いま認証する。 */
    fun onPolicyChanged(relayUrl: String) {
        val challenged = lock.withLock {
            val state = _states.value[relayUrl] as? RelayAuthState.Challenged ?: return@withLock null
            state.takeIf { account?.allowed?.invoke(relayUrl) == true }
        } ?: return
        start(relayUrl, challenged.generation, challenged.challenge)
    }

    fun onDisconnected(relayUrl: String) {
        lock.withLock {
            bumpLocked(relayUrl)
            _states.value = _states.value - relayUrl
        }
    }

    private fun start(relayUrl: String, generation: Long, challenge: String) {
        val (attempt, current) = lock.withLock {
            val current = account ?: return
            val state = _states.value[relayUrl]
            // 同じチャレンジで認証中・認証済みなら重ねない。
            if (state !is RelayAuthState.Challenged || state.generation != generation || state.challenge != challenge) return
            val attempt = bumpLocked(relayUrl)
            update(relayUrl, RelayAuthState.Authenticating(generation, challenge, current.pubkey))
            attempt to current
        }
        scope.launch {
            val outcome: Pair<Boolean, String> = try {
                val event = current.signer.sign(relayUrl, challenge)
                val ok = sender.send(relayUrl, generation, event)
                when {
                    // 応答が無い・接続が変わった。接続が変わったときは試行番号で下の結果ごと捨てる。
                    ok == null -> false to "リレーが応答しませんでした"
                    ok.accepted -> true to ""
                    else -> false to ok.message
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                false to (e.message ?: e::class.simpleName.orEmpty())
            }
            val result = lock.withLock {
                if (attempts[relayUrl] != attempt || account !== current) return@launch
                val (accepted, reason) = outcome
                if (accepted) {
                    update(relayUrl, RelayAuthState.Authenticated(generation, current.pubkey))
                    RelayAuthResult.Succeeded(relayUrl, generation)
                } else {
                    update(relayUrl, RelayAuthState.Failed(generation, current.pubkey, reason))
                    RelayAuthResult.Failed(relayUrl, generation, reason)
                }
            }
            _results.emit(result)
        }
    }

    private fun bumpLocked(relayUrl: String): Long {
        val next = (attempts[relayUrl] ?: 0L) + 1
        attempts[relayUrl] = next
        return next
    }

    private fun update(relayUrl: String, state: RelayAuthState) {
        _states.value = _states.value + (relayUrl to state)
    }

    /** [keepPubkey] 以外の身元で認証済み・認証中・失敗の状態を消し、認証済み・認証中だった URL を返す。 */
    private fun dropAuthenticatedLocked(keepPubkey: String?): Set<String> {
        val stale = mutableSetOf<String>()
        val next = _states.value.mapNotNull { (url, state) ->
            val pubkey = when (state) {
                is RelayAuthState.Authenticated -> state.pubkey.also { if (it != keepPubkey) stale += url }
                is RelayAuthState.Authenticating -> state.pubkey.also { if (it != keepPubkey) stale += url }
                is RelayAuthState.Failed -> state.pubkey
                is RelayAuthState.Challenged -> return@mapNotNull url to state
            }
            when {
                pubkey != keepPubkey -> {
                    bumpLocked(url)
                    null
                }
                // 同じアカウントでも署名器が替わるので、認証中の試行は結果を捨てられる。チャレンジからやり直す。
                state is RelayAuthState.Authenticating -> {
                    bumpLocked(url)
                    url to RelayAuthState.Challenged(state.generation, state.challenge)
                }
                else -> url to state
            }
        }.toMap()
        _states.value = next
        return stale
    }
}

/** `CLOSED auth-required:` を受けた購読をどう扱うか。 */
internal enum class AuthClosedDecision {
    /** 認証の問題ではない、または認証しないリレー。従来どおり CLOSED として扱う。 */
    NotAuth,
    /** REQ が認証より先に着いて拒否された。認証は済んでいるので、すぐ送り直す。 */
    RetryNow,
    /** 認証の結果を待ち、成功したら送り直す。 */
    AwaitAuth,
}

internal fun decideAuthClosed(
    disposition: RetryDisposition,
    canAuthenticate: Boolean,
    authenticatedNow: Boolean,
    /** この接続で認証に失敗している。もう結果は来ないので待たない。 */
    authFailedNow: Boolean,
    /** この接続で、認証のあとに一度送り直している。また拒否されたら確定する（REQ と CLOSED を繰り返さない）。 */
    alreadyRetried: Boolean,
): AuthClosedDecision = when {
    disposition != RetryDisposition.RetryAfterAuth || !canAuthenticate -> AuthClosedDecision.NotAuth
    alreadyRetried || authFailedNow -> AuthClosedDecision.NotAuth
    authenticatedNow -> AuthClosedDecision.RetryNow
    else -> AuthClosedDecision.AwaitAuth
}

/** `auth-required:` のあとの認証待ちを、時間が来たときにどうするか。 */
internal enum class AuthWaitVerdict { Done, KeepWaiting, GiveUp }

/**
 * [state] はそのリレーの今の認証状態（チャレンジが来ていなければ null）。
 * 署名器の承認待ち（認証中）は [maxMillis] まで待つ。チャレンジが来ない・認証が始まらないときは諦める。
 */
internal fun authWaitVerdict(state: RelayAuthState?, generation: Long, waitedMillis: Long, maxMillis: Long): AuthWaitVerdict = when {
    state == null -> AuthWaitVerdict.GiveUp
    state.generation != generation -> AuthWaitVerdict.Done
    state is RelayAuthState.Authenticated || state is RelayAuthState.Failed -> AuthWaitVerdict.Done
    state is RelayAuthState.Authenticating && waitedMillis < maxMillis -> AuthWaitVerdict.KeepWaiting
    else -> AuthWaitVerdict.GiveUp
}
