package com.nostr.torinos.ui.status

import com.nostr.torinos.account.AccountSession
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.ProfileFetchPolicy
import com.nostr.torinos.network.ProfileRepository
import com.nostr.torinos.network.RelayTarget
import com.nostr.torinos.status.GENERAL_STATUS_IDENTIFIER
import com.nostr.torinos.status.MUSIC_STATUS_IDENTIFIER
import com.nostr.torinos.status.PublishStatusCommand
import com.nostr.torinos.status.STATUS_EVENT_KIND
import com.nostr.torinos.status.StatusEntry
import com.nostr.torinos.status.StatusEventCodec
import com.nostr.torinos.status.StatusEventReducer
import com.nostr.torinos.status.StatusPublishResult
import com.nostr.torinos.status.StatusPublishState
import com.nostr.torinos.status.StatusPublishTarget
import com.nostr.torinos.status.StatusPublisher
import com.nostr.torinos.status.StatusSnapshot
import com.nostr.torinos.ui.SafeViewModel
import kotlin.time.Clock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

private val DEFAULT_CATEGORIES = listOf(GENERAL_STATUS_IDENTIFIER, MUSIC_STATUS_IDENTIFIER)

enum class StatusLoadState {
    Idle,
    Loading,
    Ready,
}

data class StatusState(
    val statuses: List<StatusEntry> = emptyList(),
    val ownStatuses: Map<String, StatusEntry> = emptyMap(),
    val availableCategories: List<String> = DEFAULT_CATEGORIES,
    val selectedCategories: Set<String> = DEFAULT_CATEGORIES.toSet(),
    val profiles: Map<String, NostrProfile> = emptyMap(),
    val loadState: StatusLoadState = StatusLoadState.Idle,
    val publishState: StatusPublishState = StatusPublishState.Idle,
)

internal interface StatusSubscriptionGateway {
    fun events(subscriptionId: String): Flow<NostrEvent>
    fun eose(subscriptionId: String): Flow<Unit>
    suspend fun subscribe(subscriptionId: String, filters: List<NostrFilter>, relayUrl: String)
    fun close(subscriptionId: String)
}

private object NostrStatusSubscriptionGateway : StatusSubscriptionGateway {
    override fun events(subscriptionId: String): Flow<NostrEvent> = NostrRepository.events(subscriptionId)
    override fun eose(subscriptionId: String): Flow<Unit> = NostrRepository.eose(subscriptionId)

    override suspend fun subscribe(subscriptionId: String, filters: List<NostrFilter>, relayUrl: String) {
        NostrRepository.subscribe(subscriptionId, filters, RelayTarget.Single(relayUrl))
    }

    override fun close(subscriptionId: String) = NostrRepository.close(subscriptionId)
}

internal interface StatusProfileGateway {
    fun observeChanges(): Flow<Set<String>>
    fun getCached(pubkeys: Set<String>): Map<String, NostrProfile>
    suspend fun awaitProfiles(pubkeys: Set<String>, relayHint: String)
}

private object RepositoryStatusProfileGateway : StatusProfileGateway {
    override fun observeChanges(): Flow<Set<String>> = ProfileRepository.observeChanges()
    override fun getCached(pubkeys: Set<String>): Map<String, NostrProfile> =
        ProfileRepository.getCached(pubkeys)

    /** 取得の依頼だけでなく、届くか待ちの上限まで待つ（最初の表示で名前をそろえて出すため）。 */
    override suspend fun awaitProfiles(pubkeys: Set<String>, relayHint: String) {
        ProfileRepository.awaitProfiles(
            pubkeys,
            ProfileFetchPolicy.CacheFirst(PROFILE_MAX_AGE_MS),
            relayHint = relayHint,
        )
    }
}

internal class StatusViewModel(
    private val accountSession: AccountSession? = null,
    private val subscriptions: StatusSubscriptionGateway = NostrStatusSubscriptionGateway,
    private val profileGateway: StatusProfileGateway = RepositoryStatusProfileGateway,
    private val publisher: StatusPublisher = StatusPublisher(
        signer = accountSession?.signer,
        customEmojis = { accountSession?.customEmojis?.preferences?.value?.available.orEmpty() },
        setAddressOf = { accountSession?.customEmojis?.preferences?.value?.setAddressOf(it) },
    ),
    private val nowEpochSeconds: () -> Long = { Clock.System.now().epochSeconds },
) : SafeViewModel() {
    private val _state = MutableStateFlow(StatusState())
    val state: StateFlow<StatusState> = _state.asStateFlow()

    private val instanceKey = nextInstanceKey()
    private var snapshot = StatusSnapshot()
    private val pendingPubkeys = linkedSetOf<String>()
    private val sessionJobs = mutableListOf<Job>()
    private var profileBatchJob: Job? = null
    private var revealJob: Job? = null
    private var publishJob: Job? = null
    private var activeRelayUrl: String? = null
    private var lastRelayUrl: String? = null
    private var statusSubId: String? = null
    private var ownStatusSubId: String? = null
    private var generation = 0L

    fun start(relayUrl: String) {
        if (activeRelayUrl == relayUrl) return
        val reusingSnapshot = lastRelayUrl == relayUrl
        stop()
        activeRelayUrl = relayUrl
        lastRelayUrl = relayUrl
        val currentGeneration = ++generation
        val relayKey = relayUrl.hashCode().toString()
        val currentStatusSubId = "status-$relayKey-$instanceKey-$currentGeneration"
        val currentOwnSubId = "status-own-$relayKey-$instanceKey-$currentGeneration"
        statusSubId = currentStatusSubId
        ownStatusSubId = currentOwnSubId

        // 同じリレーに戻ったときは前回の結果を残し、それより新しい分だけを取る（同じ秒を含める）。
        val since = if (reusingSnapshot) snapshot.latestByAddress.values.maxOfOrNull { it.event.createdAt } else null
        if (!reusingSnapshot) {
            snapshot = StatusSnapshot()
            pendingPubkeys.clear()
            _state.value = StatusState(loadState = StatusLoadState.Loading)
        } else {
            _state.update {
                it.copy(loadState = if (it.statuses.isEmpty()) StatusLoadState.Loading else StatusLoadState.Ready)
            }
            // 前回の表示中に取り切れなかったプロフィールを取り直す。
            if (_state.value.loadState == StatusLoadState.Ready && pendingPubkeys.isNotEmpty()) launchProfileBatch()
        }

        sessionJobs += launch {
            subscriptions.events(currentStatusSubId).collect { event ->
                if (isCurrent(currentGeneration, relayUrl)) rememberStatus(event)
            }
        }
        sessionJobs += launch {
            subscriptions.events(currentOwnSubId).collect { event ->
                if (isCurrent(currentGeneration, relayUrl) && event.pubkey == accountSession?.pubkey) {
                    rememberStatus(event)
                }
            }
        }
        sessionJobs += launch {
            subscriptions.eose(currentStatusSubId).collect {
                if (isCurrent(currentGeneration, relayUrl)) revealAfterProfiles(currentGeneration, relayUrl)
            }
        }
        sessionJobs += launch {
            delay(INITIAL_LOAD_TIMEOUT_MS)
            if (isCurrent(currentGeneration, relayUrl)) revealAfterProfiles(currentGeneration, relayUrl)
        }
        sessionJobs += launch {
            profileGateway.observeChanges().collect { changedPubkeys ->
                if (!isCurrent(currentGeneration, relayUrl)) return@collect
                val currentProfiles = _state.value.profiles
                val statusPubkeys = snapshot.latestByAddress.keys.mapTo(linkedSetOf()) { it.pubkey }
                val targets = pendingPubkeys + currentProfiles.keys + statusPubkeys
                val affected = if (changedPubkeys.isEmpty()) targets else changedPubkeys.intersect(targets)
                if (affected.isEmpty()) return@collect
                val profiles = currentProfiles - affected + profileGateway.getCached(affected)
                _state.update { state ->
                    if (state.profiles == profiles) state else state.copy(profiles = profiles)
                }
            }
        }
        sessionJobs += launch {
            accountSession?.muteStore?.mutedPubkeys?.collect {
                if (isCurrent(currentGeneration, relayUrl)) rebuildStatuses()
            }
        }
        sessionJobs += launch {
            while (isCurrent(currentGeneration, relayUrl)) {
                delay(EXPIRATION_REFRESH_INTERVAL_MS)
                if (isCurrent(currentGeneration, relayUrl)) rebuildStatuses()
            }
        }
        sessionJobs += launch {
            subscriptions.subscribe(currentStatusSubId, statusFilters(since), relayUrl)
            accountSession?.pubkey?.let { ownPubkey ->
                if (!isCurrent(currentGeneration, relayUrl)) return@let
                subscriptions.subscribe(
                    currentOwnSubId,
                    listOf(
                        NostrFilter(
                            kinds = listOf(STATUS_EVENT_KIND),
                            authors = listOf(ownPubkey),
                            since = since,
                            limit = OWN_STATUS_LIMIT,
                        ),
                    ),
                    relayUrl,
                )
            }
        }
    }

    fun stop() {
        generation++
        activeRelayUrl = null
        sessionJobs.forEach(Job::cancel)
        sessionJobs.clear()
        profileBatchJob?.cancel()
        profileBatchJob = null
        revealJob?.cancel()
        revealJob = null
        publishJob?.cancel()
        publishJob = null
        statusSubId?.let(subscriptions::close)
        ownStatusSubId?.let(subscriptions::close)
        statusSubId = null
        ownStatusSubId = null
        _state.update {
            it.copy(
                loadState = StatusLoadState.Idle,
                publishState = StatusPublishState.Idle,
            )
        }
    }

    fun toggleCategory(category: String) {
        val current = _state.value.selectedCategories
        val updated = if (category in current) current - category else current + category
        _state.update { it.copy(selectedCategories = updated) }
        rebuildStatuses()
    }

    fun clearError() {
        _state.update {
            if (it.publishState is StatusPublishState.Failed) {
                it.copy(publishState = StatusPublishState.Idle)
            } else {
                it
            }
        }
    }

    fun consumePublishResult(eventId: String) {
        _state.update {
            val succeeded = it.publishState as? StatusPublishState.Succeeded
            if (succeeded?.eventId == eventId) it.copy(publishState = StatusPublishState.Idle) else it
        }
    }

    fun publishStatus(identifier: String, content: String, expiration: Long?, referenceUrl: String?) {
        publishStatusCommand(PublishStatusCommand(identifier, content, expiration, referenceUrl))
    }

    fun deleteStatus(identifier: String) {
        publishStatusCommand(
            PublishStatusCommand(
                identifier = identifier,
                content = "",
                expiration = null,
                referenceUrl = null,
                isDeletion = true,
            ),
        )
    }

    private fun publishStatusCommand(command: PublishStatusCommand) {
        val relayUrl = activeRelayUrl
        if (relayUrl == null) {
            _state.update {
                it.copy(publishState = StatusPublishState.Failed("リレーが選択されていません"))
            }
            return
        }
        if (_state.value.publishState is StatusPublishState.Publishing) return
        val expectedGeneration = generation
        publishJob = launch {
            _state.update { it.copy(publishState = StatusPublishState.Publishing) }
            when (
                val result = publisher.publish(
                    command,
                    StatusPublishTarget.SelectedRelay(relayUrl),
                )
            ) {
                is StatusPublishResult.Published -> {
                    if (!isCurrent(expectedGeneration, relayUrl)) return@launch
                    rememberStatus(result.event)
                    _state.update {
                        it.copy(publishState = StatusPublishState.Succeeded(result.event.id))
                    }
                }
                is StatusPublishResult.Rejected -> {
                    if (!isCurrent(expectedGeneration, relayUrl)) return@launch
                    _state.update { it.copy(publishState = StatusPublishState.Failed(result.message)) }
                }
            }
        }
    }

    private fun isCurrent(expectedGeneration: Long, relayUrl: String): Boolean =
        generation == expectedGeneration && activeRelayUrl == relayUrl

    private fun rememberStatus(event: NostrEvent) {
        val status = StatusEventCodec.parse(event) ?: return
        val updated = StatusEventReducer.reduce(snapshot, status)
        if (updated == snapshot) return
        snapshot = updated
        if (status.content.isNotBlank()) scheduleProfileFetch(status.address.pubkey)
        rebuildStatuses()
    }

    private fun rebuildStatuses() {
        val selectedCategories = _state.value.selectedCategories
        val now = nowEpochSeconds()
        val mutedPubkeys = accountSession?.muteStore?.mutedPubkeys?.value.orEmpty()
        val activeStatuses = StatusEventReducer.activeStatuses(snapshot, now, mutedPubkeys)
        val extraCategories = activeStatuses
            .asSequence()
            .map(StatusEntry::identifier)
            .filter { it !in DEFAULT_CATEGORIES }
            .plus(
                selectedCategories
                    .asSequence()
                    .filter { it !in DEFAULT_CATEGORIES },
            )
            .distinct()
            .sorted()
            .toList()
        val ownStatuses = activeStatuses
            .filter { it.address.pubkey == accountSession?.pubkey }
            .associateBy(StatusEntry::identifier)
        _state.update {
            it.copy(
                statuses = StatusEventReducer.visibleStatuses(
                    snapshot,
                    now,
                    selectedCategories,
                    mutedPubkeys,
                ),
                ownStatuses = ownStatuses,
                availableCategories = DEFAULT_CATEGORIES + extraCategories,
            )
        }
    }

    /**
     * 最初の表示。名前が16進のまま出て後から差し替わらないよう、EOSE の後に、受け取った人のプロフィール取得を
     * [REVEAL_PROFILE_WAIT_MS]まで待ってから一覧を出す。取得はそのまま続け、届いた分は後から反映する。
     */
    private fun revealAfterProfiles(expectedGeneration: Long, relayUrl: String) {
        if (_state.value.loadState == StatusLoadState.Ready || revealJob?.isActive == true) return
        val requested = pendingPubkeys.toSet()
        pendingPubkeys.removeAll(requested)
        revealJob = launch {
            val fetch = launch {
                var fetchedAll = false
                try {
                    if (requested.isNotEmpty()) profileGateway.awaitProfiles(requested, relayUrl)
                    fetchedAll = true
                } finally {
                    // 途中で止めた（タブを離れた）ときは、次に表示したときに取り直す。
                    if (!fetchedAll) pendingPubkeys.addAll(requested)
                }
                val fetched = profileGateway.getCached(requested)
                if (fetched.isNotEmpty() && isCurrent(expectedGeneration, relayUrl)) {
                    _state.update { it.copy(profiles = it.profiles + fetched) }
                }
            }
            withTimeoutOrNull(REVEAL_PROFILE_WAIT_MS) { fetch.join() }
            if (isCurrent(expectedGeneration, relayUrl)) {
                _state.update { it.copy(loadState = StatusLoadState.Ready) }
            }
        }
    }

    private fun scheduleProfileFetch(pubkey: String) {
        if (pubkey in _state.value.profiles || !pendingPubkeys.add(pubkey)) return
        val cached = profileGateway.getCached(setOf(pubkey))
        if (cached.isNotEmpty()) {
            _state.update { it.copy(profiles = it.profiles + cached) }
        }
        // 最初の表示の前は、EOSE でまとめて取る（revealAfterProfiles）。
        if (_state.value.loadState != StatusLoadState.Ready) return
        launchProfileBatch()
    }

    private fun launchProfileBatch() {
        profileBatchJob?.cancel()
        val relayUrl = activeRelayUrl ?: return
        val expectedGeneration = generation
        profileBatchJob = launch {
            delay(PROFILE_BATCH_DELAY_MS)
            if (!isCurrent(expectedGeneration, relayUrl)) return@launch
            val requested = pendingPubkeys.toSet()
            pendingPubkeys.removeAll(requested)
            if (requested.isNotEmpty()) profileGateway.awaitProfiles(requested, relayUrl)
        }
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }

    private companion object {
        const val OWN_STATUS_LIMIT = 100
        const val INITIAL_LOAD_TIMEOUT_MS = 5_000L
        const val EXPIRATION_REFRESH_INTERVAL_MS = 60_000L
        const val PROFILE_BATCH_DELAY_MS = 300L
        const val REVEAL_PROFILE_WAIT_MS = 1_000L
        var nextKey = 0
        fun nextInstanceKey(): Int = nextKey++
    }
}

private const val PROFILE_MAX_AGE_MS = 15 * 60 * 1_000L

/**
 * ステータス一覧の購読フィルター。他のアプリが大量に出す種類（`security:scan:…` など）で件数の上限が埋まらないよう、
 * 表示する一般・音楽を種類で絞って取り、それ以外の種類は見つけるために少しだけ取る。
 */
internal fun statusFilters(since: Long?): List<NostrFilter> = listOf(
    NostrFilter(
        kinds = listOf(STATUS_EVENT_KIND),
        dTags = DEFAULT_CATEGORIES,
        since = since,
        limit = STATUS_DEFAULT_CATEGORY_LIMIT,
    ),
    NostrFilter(kinds = listOf(STATUS_EVENT_KIND), since = since, limit = STATUS_OTHER_CATEGORY_LIMIT),
)

private const val STATUS_DEFAULT_CATEGORY_LIMIT = 200
private const val STATUS_OTHER_CATEGORY_LIMIT = 50
