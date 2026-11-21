package com.nostr.torinos.ui.profile

import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.network.NostrRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class FollowerCountLoadState(
    val liveCount: Int = 0,
    val isLoading: Boolean = false,
)

/**
 * プロフィールのフォロワー数を数える。押されたときだけ動き、終わったら [FollowerCountStore] に残す。
 * 自分・他人のプロフィールで共通。[launch] には ViewModel の launch を渡す。
 */
internal class FollowerCountLoader(
    private val pubkey: String,
    private val subId: String,
    private val launch: (suspend CoroutineScope.() -> Unit) -> Job,
    private val relayUrl: String? = null,
    private val store: FollowerCountStore = FollowerCountStore.shared,
    private val limit: Int = FOLLOWERS_FETCH_LIMIT,
    private val timeoutMillis: Long = FOLLOWERS_LOAD_TIMEOUT_MS,
) {
    private val mutableState = MutableStateFlow(FollowerCountLoadState())
    val state: StateFlow<FollowerCountLoadState> = mutableState.asStateFlow()
    private var job: Job? = null

    init {
        launch { store.ensureLoaded() }
    }

    fun start() {
        if (job?.isActive == true) return
        mutableState.value = FollowerCountLoadState(liveCount = 0, isLoading = true)
        job = launch {
            val pubkeys = linkedSetOf<String>()
            val eventIds = hashSetOf<String>()
            val completion = CompletableDeferred<Unit>()
            // 購読前から受け始め、接続直後の EOSE/CLOSED を取り逃がさない。
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                NostrRepository.events(subId).collect { event ->
                    if (event.kind != 3 || !eventIds.add(event.id)) return@collect
                    if (pubkeys.add(event.pubkey)) {
                        mutableState.update { it.copy(liveCount = pubkeys.size) }
                    }
                }
            }
            val eose = launch(start = CoroutineStart.UNDISPATCHED) {
                NostrRepository.eose(subId).collect { completion.complete(Unit) }
            }
            try {
                NostrRepository.close(subId)
                NostrRepository.subscribe(
                    subId,
                    NostrFilter(kinds = listOf(3), pTags = listOf(pubkey), limit = limit),
                    relayUrl = relayUrl,
                )
                // EOSE でも時間切れでも、ここまで来れば中断ではない。
                awaitFollowerRelayCompletion(completion, timeoutMillis)
                if (shouldRecordFollowerCount(cancelled = false, count = pubkeys.size)) {
                    store.record(
                        pubkey = pubkey,
                        count = pubkeys.size,
                        limited = isFollowerCountLimited(eventIds.size, limit),
                        followers = pubkeys.toSet(),
                    )
                }
            } finally {
                collector.cancel()
                eose.cancel()
                NostrRepository.close(subId)
                mutableState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun close() {
        job?.cancel()
        NostrRepository.close(subId)
    }

    companion object {
        const val FOLLOWERS_FETCH_LIMIT = 500
        private const val FOLLOWERS_LOAD_TIMEOUT_MS = 10_000L
    }
}
