package com.nostr.torinos.ui.live

import com.nostr.torinos.crypto.KeyStorage
import com.nostr.torinos.crypto.signEvent
import com.nostr.torinos.model.LiveActivityItem
import com.nostr.torinos.model.LiveActivityStatus
import com.nostr.torinos.model.NIP53_LIVE_ACTIVITY_KIND
import com.nostr.torinos.model.NIP53_LIVE_CHAT_KIND
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.NostrProfile
import com.nostr.torinos.model.latestLiveActivityVersions
import com.nostr.torinos.model.liveActivityAddress
import com.nostr.torinos.model.toLiveActivityMeta
import com.nostr.torinos.network.ImageUploader
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.ProfileCache
import com.nostr.torinos.ui.SafeViewModel
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

data class LiveListState(
    val activities: List<LiveActivityItem> = emptyList(),
    val profiles: Map<String, NostrProfile> = emptyMap(),
    val selectedStatuses: Set<LiveActivityStatus> = setOf(LiveActivityStatus.Live, LiveActivityStatus.Planned),
    val isInitialLoad: Boolean = true,
    val error: String? = null,
)

data class LiveDetailState(
    val activity: LiveActivityItem? = null,
    val chatMessages: List<NostrEvent> = emptyList(),
    val profiles: Map<String, NostrProfile> = emptyMap(),
    val isInitialLoad: Boolean = true,
    val isPublishing: Boolean = false,
    val publishCompletedCount: Int = 0,
    val deleteCompletedCount: Int = 0,
    val error: String? = null,
)

enum class LiveStartMode {
    Now,
    Scheduled,
}

data class LiveCreateState(
    val title: String = "",
    val summary: String = "",
    val streamingUrl: String = "",
    val image: LiveImageAttachment? = null,
    val topicsText: String = "",
    val startMode: LiveStartMode = LiveStartMode.Now,
    val scheduledDate: String = "",
    val scheduledTime: String = "",
    val isPublishing: Boolean = false,
    val publishCompletedCount: Int = 0,
    val publishedEvent: NostrEvent? = null,
    val error: String? = null,
) {
    val isUploadingImage: Boolean get() = image?.isUploading == true
    val canPublish: Boolean get() = title.isNotBlank() && !isPublishing && !isUploadingImage
}

data class LiveImageAttachment(
    val previewBytes: ByteArray?,
    val uploadedUrl: String?,
    val isUploading: Boolean,
)

class LiveListViewModel(private val relayUrl: String? = null) : SafeViewModel() {
    private val _state = MutableStateFlow(LiveListState())
    val state: StateFlow<LiveListState> = _state.asStateFlow()

    private val instanceKey = nextInstanceKey()
    private val relayKey = relayUrl?.hashCode()?.toString() ?: "all"
    private val liveSubId = "live-list-$relayKey-$instanceKey"
    private val profileSubId = "live-list-profile-$relayKey-$instanceKey"
    private val rawEvents = linkedMapOf<String, NostrEvent>()
    private val deletedAddresses = linkedSetOf<String>()
    private val deletedEventIds = linkedSetOf<String>()
    private val pendingPubkeys = linkedSetOf<String>()
    private val jobs = mutableListOf<Job>()
    private var profileBatchJob: Job? = null

    init {
        start()
    }

    fun toggleStatus(status: LiveActivityStatus) {
        val current = _state.value.selectedStatuses
        _state.value = _state.value.copy(
            selectedStatuses = if (status in current) current - status else current + status,
        )
        rebuildActivities()
    }

    fun addPublishedActivity(event: NostrEvent) {
        rememberActivity(event)
    }

    private fun start() {
        jobs += launch {
            NostrRepository.events(liveSubId).collect { event ->
                when (event.kind) {
                    NIP53_LIVE_ACTIVITY_KIND -> rememberActivity(event)
                    NIP09_DELETION_KIND -> rememberDeletion(event)
                }
            }
        }
        jobs += launch {
            NostrRepository.events(profileSubId).collect { event ->
                if (event.kind != 0) return@collect
                val profile = ProfileCache.putEvent(event) ?: return@collect
                pendingPubkeys.remove(event.pubkey)
                _state.value = _state.value.copy(profiles = _state.value.profiles + (event.pubkey to profile))
                rebuildActivities()
            }
        }
        jobs += launch {
            NostrRepository.eose(liveSubId).collect {
                _state.value = _state.value.copy(isInitialLoad = false)
            }
        }
        jobs += launch {
            delay(INITIAL_LOAD_TIMEOUT_MS)
            if (_state.value.isInitialLoad) {
                _state.value = _state.value.copy(isInitialLoad = false)
            }
        }
        jobs += launch {
            while (true) {
                delay(LIVE_REFRESH_INTERVAL_MS)
                rebuildActivities()
            }
        }
        jobs += launch {
            NostrRepository.subscribe(
                liveSubId,
                NostrFilter(kinds = listOf(NIP53_LIVE_ACTIVITY_KIND, NIP09_DELETION_KIND), limit = LIVE_LIMIT * 2),
                relayUrl = relayUrl,
            )
        }
    }

    private fun rememberActivity(event: NostrEvent) {
        val meta = event.toLiveActivityMeta(Clock.System.now().epochSeconds) ?: return
        val address = liveActivityAddress(event.pubkey, meta.identifier)
        if (address in deletedAddresses || event.id in deletedEventIds) return
        val existing = rawEvents[address]
        if (existing != null && existing.createdAt >= event.createdAt) return
        rawEvents[address] = event
        scheduleProfileFetch(event.pubkey)
        meta.participants.forEach { scheduleProfileFetch(it.pubkey) }
        rebuildActivities()
    }

    private fun rememberDeletion(event: NostrEvent) {
        val deleted = event.tags.any { tag ->
            when (tag.firstOrNull()) {
                "a" -> {
                    val address = tag.getOrNull(1)
                    val addressPubkey = address?.split(':')?.getOrNull(1)
                    if (address != null && addressPubkey == event.pubkey) {
                        deletedAddresses += address
                        rawEvents.remove(address) != null
                    } else {
                        false
                    }
                }
                "e" -> {
                    val eventId = tag.getOrNull(1)
                    if (eventId != null) {
                        deletedEventIds += eventId
                        val matchingAddress = rawEvents.entries
                            .firstOrNull { (_, liveEvent) -> liveEvent.id == eventId && liveEvent.pubkey == event.pubkey }
                            ?.key
                        matchingAddress?.let { rawEvents.remove(it) } != null
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
        if (deleted) rebuildActivities()
    }

    private fun rebuildActivities() {
        val now = Clock.System.now().epochSeconds
        val selected = _state.value.selectedStatuses
        val requiredPubkeys = rawEvents.values.flatMap { event ->
            val meta = event.toLiveActivityMeta(now)
            if (meta == null) listOf(event.pubkey) else listOf(event.pubkey) + meta.participants.map { it.pubkey }
        }
        val profiles = _state.value.profiles + ProfileCache.getAll(requiredPubkeys)
        if (profiles != _state.value.profiles) {
            _state.value = _state.value.copy(profiles = profiles)
        }
        val activities = rawEvents.values
            .mapNotNull { event ->
                val meta = event.toLiveActivityMeta(now) ?: return@mapNotNull null
                LiveActivityItem(event = event, meta = meta, authorProfile = profiles[event.pubkey])
            }
            .latestLiveActivityVersions(now)
            .filter { selected.isEmpty() || it.meta.status in selected }
        _state.value = _state.value.copy(activities = activities)
    }

    private fun scheduleProfileFetch(pubkey: String) {
        if (pubkey in _state.value.profiles || !pendingPubkeys.add(pubkey)) return
        ProfileCache.get(pubkey)?.let { cachedProfile ->
            pendingPubkeys.remove(pubkey)
            _state.value = _state.value.copy(profiles = _state.value.profiles + (pubkey to cachedProfile))
            rebuildActivities()
            return
        }
        profileBatchJob?.cancel()
        profileBatchJob = launch {
            delay(PROFILE_BATCH_DELAY_MS)
            val pubkeys = pendingPubkeys.toList().take(PROFILE_FETCH_LIMIT)
            pendingPubkeys.removeAll(pubkeys.toSet())
            if (pubkeys.isNotEmpty()) {
                NostrRepository.subscribe(profileSubId, NostrFilter(kinds = listOf(0), authors = pubkeys), relayUrl = relayUrl)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        jobs.forEach { it.cancel() }
        profileBatchJob?.cancel()
        NostrRepository.close(liveSubId)
        NostrRepository.close(profileSubId)
    }
}

class LiveCreateViewModel(private val relayUrl: String? = null) : SafeViewModel() {
    private val _state = MutableStateFlow(
        LiveCreateState(
            scheduledDate = defaultScheduledDate(),
            scheduledTime = defaultScheduledTime(),
        ),
    )
    val state: StateFlow<LiveCreateState> = _state.asStateFlow()

    fun onTitleChange(value: String) {
        _state.value = _state.value.copy(title = value, error = null, publishedEvent = null)
    }

    fun onSummaryChange(value: String) {
        _state.value = _state.value.copy(summary = value, error = null, publishedEvent = null)
    }

    fun onStreamingUrlChange(value: String) {
        _state.value = _state.value.copy(streamingUrl = value, error = null, publishedEvent = null)
    }

    fun uploadImage(bytes: ByteArray, mimeType: String) {
        _state.value = _state.value.copy(
            image = LiveImageAttachment(
                previewBytes = bytes,
                uploadedUrl = null,
                isUploading = true,
            ),
            error = null,
            publishedEvent = null,
        )
        launch {
            ImageUploader.upload(bytes, mimeType)
                .onSuccess { url ->
                    _state.value = _state.value.copy(
                        image = _state.value.image?.copy(uploadedUrl = url, isUploading = false),
                    )
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        image = _state.value.image?.copy(isUploading = false),
                        error = "画像のアップロードに失敗しました: ${e.message}",
                    )
                }
        }
    }

    fun removeImage() {
        _state.value = _state.value.copy(image = null, error = null, publishedEvent = null)
    }

    fun onTopicsTextChange(value: String) {
        _state.value = _state.value.copy(topicsText = value, error = null, publishedEvent = null)
    }

    fun onStartModeChange(value: LiveStartMode) {
        _state.value = _state.value.copy(startMode = value, error = null, publishedEvent = null)
    }

    fun onScheduledDateChange(value: String) {
        _state.value = _state.value.copy(scheduledDate = value, error = null, publishedEvent = null)
    }

    fun onScheduledTimeChange(value: String) {
        _state.value = _state.value.copy(scheduledTime = value, error = null, publishedEvent = null)
    }

    fun publish() {
        val current = _state.value
        val title = current.title.trim()
        if (title.isBlank()) {
            _state.value = current.copy(error = "タイトルを入力してください")
            return
        }
        val now = Clock.System.now().epochSeconds
        val starts = when (current.startMode) {
            LiveStartMode.Now -> now
            LiveStartMode.Scheduled -> parseScheduledStart(current.scheduledDate, current.scheduledTime)
                ?: run {
                    _state.value = current.copy(error = "開始日時は yyyy-MM-dd と HH:mm で入力してください")
                    return
                }
        }
        if (current.startMode == LiveStartMode.Scheduled && starts <= now) {
            _state.value = current.copy(error = "予約日時は現在より後にしてください")
            return
        }
        launch {
            _state.value = _state.value.copy(isPublishing = true, error = null, publishedEvent = null)
            try {
                val privateKeyHex = KeyStorage.loadPrivateKey()
                    ?: error("秘密鍵が見つかりません")
                val identifier = "torinos-live-$now"
                val event = signEvent(
                    privateKeyHex = privateKeyHex,
                    content = current.summary.trim(),
                    kind = NIP53_LIVE_ACTIVITY_KIND,
                    tags = buildLiveActivityTags(
                        identifier = identifier,
                        title = title,
                        summary = current.summary,
                        streamingUrl = current.streamingUrl,
                        imageUrl = current.image?.uploadedUrl.orEmpty(),
                        topicsText = current.topicsText,
                        status = current.startMode.toLiveStatus(),
                        relayUrl = relayUrl,
                        starts = starts,
                    ),
                )
                if (relayUrl != null) {
                    NostrRepository.publishToRelays(event, listOf(relayUrl))
                } else {
                    NostrRepository.publish(event)
                }
                _state.value = LiveCreateState(
                    scheduledDate = defaultScheduledDate(),
                    scheduledTime = defaultScheduledTime(),
                    publishCompletedCount = _state.value.publishCompletedCount + 1,
                    publishedEvent = event,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.value = _state.value.copy(
                    isPublishing = false,
                    error = e.message ?: "ライブの投稿に失敗しました",
                )
            }
        }
    }

    fun clearPublishedEvent() {
        _state.value = _state.value.copy(publishedEvent = null)
    }

    private fun buildLiveActivityTags(
        identifier: String,
        title: String,
        summary: String,
        streamingUrl: String,
        imageUrl: String,
        topicsText: String,
        status: LiveActivityStatus,
        relayUrl: String?,
        starts: Long,
    ): List<List<String>> = buildList {
        add(listOf("d", identifier))
        add(listOf("title", title))
        summary.trim().takeIf { it.isNotBlank() }?.let { add(listOf("summary", it)) }
        streamingUrl.trim().takeIf { it.isNotBlank() }?.let { add(listOf("streaming", it)) }
        imageUrl.trim().takeIf { it.isNotBlank() }?.let { add(listOf("image", it)) }
        add(listOf("starts", starts.toString()))
        add(listOf("status", status.raw))
        relayUrl?.takeIf { it.isNotBlank() }?.let { add(listOf("relays", it)) }
        topicsText.split(',', '、', ' ', '\n')
            .map { it.trim().trimStart('#') }
            .filter { it.isNotBlank() }
            .distinct()
            .take(10)
            .forEach { add(listOf("t", it)) }
        add(listOf("client", "ToriNos"))
    }

    private fun LiveStartMode.toLiveStatus(): LiveActivityStatus = when (this) {
        LiveStartMode.Now -> LiveActivityStatus.Live
        LiveStartMode.Scheduled -> LiveActivityStatus.Planned
    }

    private fun parseScheduledStart(date: String, time: String): Long? = runCatching {
        val dateParts = date.trim().split("-")
        val timeParts = time.trim().split(":")
        if (dateParts.size != 3 || timeParts.size != 2) return null
        LocalDateTime(
            date = LocalDate(
                year = dateParts[0].toInt(),
                month = Month.entries[dateParts[1].toInt() - 1],
                day = dateParts[2].toInt(),
            ),
            time = LocalTime(
                hour = timeParts[0].toInt(),
                minute = timeParts[1].toInt(),
            ),
        ).toInstant(TimeZone.currentSystemDefault()).epochSeconds
    }.getOrNull()

    private fun defaultScheduledDate(): String {
        val dt = Instant.fromEpochSeconds(Clock.System.now().epochSeconds + DEFAULT_SCHEDULE_OFFSET_SECONDS)
            .toLocalDateTime(TimeZone.currentSystemDefault())
        return "${dt.year}-${(dt.month.ordinal + 1).twoDigits()}-${dt.day.twoDigits()}"
    }

    private fun defaultScheduledTime(): String {
        val dt = Instant.fromEpochSeconds(Clock.System.now().epochSeconds + DEFAULT_SCHEDULE_OFFSET_SECONDS)
            .toLocalDateTime(TimeZone.currentSystemDefault())
        return "${dt.hour.twoDigits()}:${dt.minute.twoDigits()}"
    }

    private fun Int.twoDigits(): String = toString().padStart(2, '0')
}

class LiveDetailViewModel(
    private val pubkey: String,
    private val identifier: String,
    private val relayUrl: String? = null,
) : SafeViewModel() {
    private val _state = MutableStateFlow(LiveDetailState())
    val state: StateFlow<LiveDetailState> = _state.asStateFlow()

    private val instanceKey = nextInstanceKey()
    private val relayKey = relayUrl?.hashCode()?.toString() ?: "all"
    private val activitySubId = "live-detail-$relayKey-$instanceKey"
    private val chatSubId = "live-chat-$relayKey-$instanceKey"
    private val profileSubId = "live-detail-profile-$relayKey-$instanceKey"
    private val address = liveActivityAddress(pubkey, identifier)
    private val chatEvents = linkedMapOf<String, NostrEvent>()
    private val pendingPubkeys = linkedSetOf<String>()
    private val jobs = mutableListOf<Job>()
    private var profileBatchJob: Job? = null

    init {
        start()
    }

    fun publishChat(content: String) {
        val body = content.trim()
        if (body.isBlank()) {
            _state.value = _state.value.copy(error = "メッセージを入力してください")
            return
        }
        launch {
            _state.value = _state.value.copy(isPublishing = true, error = null)
            try {
                val privateKeyHex = KeyStorage.loadPrivateKey()
                    ?: error("秘密鍵が見つかりません")
                val event = signEvent(
                    privateKeyHex = privateKeyHex,
                    content = body,
                    kind = NIP53_LIVE_CHAT_KIND,
                    tags = listOf(listOf("a", address, relayUrl.orEmpty(), "root")),
                )
                rememberChat(event)
                if (relayUrl != null) {
                    NostrRepository.publishToRelays(event, listOf(relayUrl))
                } else {
                    NostrRepository.publish(event)
                }
                _state.value = _state.value.copy(
                    isPublishing = false,
                    publishCompletedCount = _state.value.publishCompletedCount + 1,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.value = _state.value.copy(
                    isPublishing = false,
                    error = e.message ?: "チャットの送信に失敗しました",
                )
            }
        }
    }

    fun endLive(recordingUrl: String? = null) {
        val currentActivity = _state.value.activity ?: run {
            _state.value = _state.value.copy(error = "ライブ情報が読み込まれていません")
            return
        }
        if (currentActivity.meta.status == LiveActivityStatus.Ended) {
            _state.value = _state.value.copy(error = "このライブはすでに終了しています")
            return
        }
        launch {
            _state.value = _state.value.copy(isPublishing = true, error = null)
            try {
                val privateKeyHex = KeyStorage.loadPrivateKey()
                    ?: error("秘密鍵が見つかりません")
                val now = Clock.System.now().epochSeconds
                val event = signEvent(
                    privateKeyHex = privateKeyHex,
                    content = currentActivity.event.content,
                    kind = NIP53_LIVE_ACTIVITY_KIND,
                    tags = currentActivity.event.tags.endedLiveTags(
                        ends = now,
                        recordingUrl = recordingUrl.orEmpty(),
                    ),
                )
                if (relayUrl != null) {
                    NostrRepository.publishToRelays(event, listOf(relayUrl))
                } else {
                    NostrRepository.publish(event)
                }
                val meta = event.toLiveActivityMeta(now)
                _state.value = _state.value.copy(
                    activity = meta?.let { LiveActivityItem(event, it, _state.value.profiles[event.pubkey]) },
                    isPublishing = false,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.value = _state.value.copy(
                    isPublishing = false,
                    error = e.message ?: "ライブの終了に失敗しました",
                )
            }
        }
    }

    fun deleteLive() {
        val currentActivity = _state.value.activity ?: run {
            _state.value = _state.value.copy(error = "ライブ情報が読み込まれていません")
            return
        }
        launch {
            _state.value = _state.value.copy(isPublishing = true, error = null)
            try {
                val privateKeyHex = KeyStorage.loadPrivateKey()
                    ?: error("秘密鍵が見つかりません")
                val deletion = signEvent(
                    privateKeyHex = privateKeyHex,
                    content = "ライブを削除",
                    kind = NIP09_DELETION_KIND,
                    tags = listOf(
                        listOf("a", address),
                        listOf("e", currentActivity.event.id),
                        listOf("k", NIP53_LIVE_ACTIVITY_KIND.toString()),
                    ),
                )
                if (relayUrl != null) {
                    NostrRepository.publishToRelays(deletion, listOf(relayUrl))
                } else {
                    NostrRepository.publish(deletion)
                }
                _state.value = _state.value.copy(
                    activity = null,
                    isPublishing = false,
                    deleteCompletedCount = _state.value.deleteCompletedCount + 1,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.value = _state.value.copy(
                    isPublishing = false,
                    error = e.message ?: "ライブの削除に失敗しました",
                )
            }
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    private fun start() {
        jobs += launch {
            NostrRepository.events(activitySubId).collect { event ->
                if (event.kind != NIP53_LIVE_ACTIVITY_KIND || event.pubkey != pubkey) return@collect
                val meta = event.toLiveActivityMeta(Clock.System.now().epochSeconds) ?: return@collect
                if (meta.identifier != identifier) return@collect
                val existing = _state.value.activity?.event
                if (existing != null && existing.createdAt >= event.createdAt) return@collect
                scheduleProfileFetch(event.pubkey)
                meta.participants.forEach { scheduleProfileFetch(it.pubkey) }
                _state.value = _state.value.copy(
                    activity = LiveActivityItem(event, meta, _state.value.profiles[event.pubkey]),
                    isInitialLoad = false,
                )
            }
        }
        jobs += launch {
            NostrRepository.events(chatSubId).collect { event ->
                if (event.kind != NIP53_LIVE_CHAT_KIND) return@collect
                if (event.tags.none { it.firstOrNull() == "a" && it.getOrNull(1) == address }) return@collect
                rememberChat(event)
            }
        }
        jobs += launch {
            NostrRepository.events(profileSubId).collect { event ->
                if (event.kind != 0) return@collect
                val profile = ProfileCache.putEvent(event) ?: return@collect
                pendingPubkeys.remove(event.pubkey)
                _state.value = _state.value.copy(profiles = _state.value.profiles + (event.pubkey to profile))
                refreshProfilesOnItems()
            }
        }
        jobs += launch {
            NostrRepository.eose(activitySubId).collect {
                _state.value = _state.value.copy(isInitialLoad = false)
            }
        }
        jobs += launch {
            delay(INITIAL_LOAD_TIMEOUT_MS)
            if (_state.value.isInitialLoad) {
                _state.value = _state.value.copy(isInitialLoad = false)
            }
        }
        jobs += launch {
            NostrRepository.subscribe(
                activitySubId,
                NostrFilter(kinds = listOf(NIP53_LIVE_ACTIVITY_KIND), authors = listOf(pubkey), dTags = listOf(identifier), limit = 5),
                relayUrl = relayUrl,
            )
            NostrRepository.subscribe(
                chatSubId,
                NostrFilter(kinds = listOf(NIP53_LIVE_CHAT_KIND), aTags = listOf(address), limit = CHAT_LIMIT),
                relayUrl = relayUrl,
            )
        }
    }

    private fun rememberChat(event: NostrEvent) {
        if (chatEvents.containsKey(event.id)) return
        chatEvents[event.id] = event
        scheduleProfileFetch(event.pubkey)
        _state.value = _state.value.copy(
            chatMessages = chatEvents.values.sortedBy { it.createdAt },
        )
    }

    private fun refreshProfilesOnItems() {
        val activity = _state.value.activity
        _state.value = _state.value.copy(
            activity = activity?.copy(authorProfile = _state.value.profiles[activity.event.pubkey]),
        )
    }

    private fun scheduleProfileFetch(pubkey: String) {
        if (pubkey in _state.value.profiles || !pendingPubkeys.add(pubkey)) return
        ProfileCache.get(pubkey)?.let { cachedProfile ->
            pendingPubkeys.remove(pubkey)
            _state.value = _state.value.copy(profiles = _state.value.profiles + (pubkey to cachedProfile))
            refreshProfilesOnItems()
            return
        }
        profileBatchJob?.cancel()
        profileBatchJob = launch {
            delay(PROFILE_BATCH_DELAY_MS)
            val pubkeys = pendingPubkeys.toList().take(PROFILE_FETCH_LIMIT)
            pendingPubkeys.removeAll(pubkeys.toSet())
            if (pubkeys.isNotEmpty()) {
                NostrRepository.subscribe(profileSubId, NostrFilter(kinds = listOf(0), authors = pubkeys), relayUrl = relayUrl)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        jobs.forEach { it.cancel() }
        profileBatchJob?.cancel()
        NostrRepository.close(activitySubId)
        NostrRepository.close(chatSubId)
        NostrRepository.close(profileSubId)
    }

    private fun List<List<String>>.endedLiveTags(
        ends: Long,
        recordingUrl: String,
    ): List<List<String>> = buildList {
        this@endedLiveTags
            .filterNot { tag ->
                when (tag.firstOrNull()) {
                    "status", "ends", "recording" -> true
                    else -> false
                }
            }
            .forEach { add(it) }
        add(listOf("status", LiveActivityStatus.Ended.raw))
        add(listOf("ends", ends.toString()))
        recordingUrl.trim().takeIf { it.isNotBlank() }?.let { add(listOf("recording", it)) }
        add(listOf("client", "ToriNos"))
    }
}

private const val LIVE_LIMIT = 200
private const val NIP09_DELETION_KIND = 5
private const val CHAT_LIMIT = 500
private const val PROFILE_FETCH_LIMIT = 200
private const val INITIAL_LOAD_TIMEOUT_MS = 5_000L
private const val PROFILE_BATCH_DELAY_MS = 300L
private const val LIVE_REFRESH_INTERVAL_MS = 60_000L
private const val DEFAULT_SCHEDULE_OFFSET_SECONDS = 60L * 60L

private var nextKey = 0
private fun nextInstanceKey(): Int = nextKey++
