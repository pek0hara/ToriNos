package com.nostr.torinos.model

const val NIP53_LIVE_ACTIVITY_KIND = 30311
const val NIP53_LIVE_CHAT_KIND = 1311

enum class LiveActivityStatus(val raw: String, val label: String) {
    Live("live", "LIVE"),
    Planned("planned", "予定"),
    Ended("ended", "終了"),
    Unknown("unknown", "不明"),
}

data class LiveParticipant(
    val pubkey: String,
    val relay: String?,
    val role: String?,
    val proof: String?,
)

data class LiveActivityMeta(
    val identifier: String,
    val title: String?,
    val summary: String?,
    val imageUrl: String?,
    val streamingUrl: String?,
    val recordingUrl: String?,
    val starts: Long?,
    val ends: Long?,
    val status: LiveActivityStatus,
    val currentParticipants: Int?,
    val totalParticipants: Int?,
    val topics: List<String>,
    val participants: List<LiveParticipant>,
    val relays: List<String>,
    val pinnedEventIds: List<String>,
)

data class LiveActivityItem(
    val event: NostrEvent,
    val meta: LiveActivityMeta,
    val authorProfile: NostrProfile? = null,
) {
    val address: String get() = liveActivityAddress(event.pubkey, meta.identifier)
    val displayTitle: String get() = meta.title?.takeIf { it.isNotBlank() } ?: "無題のライブ"
    val displaySummary: String get() = meta.summary?.takeIf { it.isNotBlank() } ?: ""
}

fun liveActivityAddress(pubkey: String, identifier: String): String =
    "$NIP53_LIVE_ACTIVITY_KIND:$pubkey:$identifier"

fun NostrEvent.toLiveActivityMeta(now: Long? = null): LiveActivityMeta? {
    if (kind != NIP53_LIVE_ACTIVITY_KIND) return null
    val identifier = tagValue("d")?.takeIf { it.isNotBlank() } ?: return null
    val starts = tagValue("starts")?.toLongOrNull()
    val ends = tagValue("ends")?.toLongOrNull()
    val streamingUrl = tagValue("streaming")
    val recordingUrl = tagValue("recording")
    val rawStatus = tagValue("status")?.lowercase()
    val status = when (rawStatus) {
        LiveActivityStatus.Live.raw -> LiveActivityStatus.Live
        LiveActivityStatus.Planned.raw -> LiveActivityStatus.Planned
        LiveActivityStatus.Ended.raw -> LiveActivityStatus.Ended
        else -> inferLiveActivityStatus(
            now = now,
            starts = starts,
            ends = ends,
            streamingUrl = streamingUrl,
            recordingUrl = recordingUrl,
        )
    }.let { parsed ->
        if (parsed == LiveActivityStatus.Live && now != null && createdAt <= now - LIVE_STALE_SECONDS) {
            LiveActivityStatus.Ended
        } else {
            parsed
        }
    }
    return LiveActivityMeta(
        identifier = identifier,
        title = tagValue("title"),
        summary = tagValue("summary"),
        imageUrl = tagValue("image"),
        streamingUrl = streamingUrl,
        recordingUrl = recordingUrl,
        starts = starts,
        ends = ends,
        status = status,
        currentParticipants = tagValue("current_participants")?.toIntOrNull(),
        totalParticipants = tagValue("total_participants")?.toIntOrNull(),
        topics = tags.filter { it.firstOrNull() == "t" }
            .mapNotNull { it.getOrNull(1)?.takeIf { topic -> topic.isNotBlank() } },
        participants = tags.filter { it.firstOrNull() == "p" }
            .mapNotNull { tag ->
                val pubkey = tag.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                LiveParticipant(
                    pubkey = pubkey,
                    relay = tag.getOrNull(2)?.takeIf { it.isNotBlank() },
                    role = tag.getOrNull(3)?.takeIf { it.isNotBlank() },
                    proof = tag.getOrNull(4)?.takeIf { it.isNotBlank() },
                )
            },
        relays = tags.firstOrNull { it.firstOrNull() == "relays" }
            ?.drop(1)
            ?.mapNotNull { it.takeIf { relay -> relay.isNotBlank() } }
            ?: emptyList(),
        pinnedEventIds = tags.filter { it.firstOrNull() == "pinned" }
            .mapNotNull { it.getOrNull(1)?.takeIf { id -> id.isNotBlank() } },
    )
}

fun List<LiveActivityItem>.latestLiveActivityVersions(now: Long): List<LiveActivityItem> =
    groupBy { it.address }
        .values
        .mapNotNull { versions -> versions.maxByOrNull { it.event.createdAt } }
        .mapNotNull { item ->
            item.event.toLiveActivityMeta(now)?.let { item.copy(meta = it) }
        }
        .sortedWith(
            compareByDescending<LiveActivityItem> { it.meta.status == LiveActivityStatus.Live }
                .thenByDescending { it.meta.status == LiveActivityStatus.Planned }
                .thenBy { if (it.meta.status == LiveActivityStatus.Planned) it.meta.starts ?: Long.MAX_VALUE else Long.MAX_VALUE }
                .thenByDescending { it.meta.starts ?: it.event.createdAt }
                .thenByDescending { it.event.createdAt },
        )

private const val LIVE_STALE_SECONDS = 60L * 60L

private fun inferLiveActivityStatus(
    now: Long?,
    starts: Long?,
    ends: Long?,
    streamingUrl: String?,
    recordingUrl: String?,
): LiveActivityStatus {
    if (now != null) {
        if (ends != null && ends <= now) return LiveActivityStatus.Ended
        if (starts != null && starts > now) return LiveActivityStatus.Planned
        if (starts != null && starts <= now && (ends == null || ends > now)) return LiveActivityStatus.Live
    }
    if (!recordingUrl.isNullOrBlank() && streamingUrl.isNullOrBlank()) return LiveActivityStatus.Ended
    if (!streamingUrl.isNullOrBlank()) return LiveActivityStatus.Live
    return LiveActivityStatus.Unknown
}

private fun NostrEvent.tagValue(name: String): String? =
    tags.firstOrNull { it.firstOrNull() == name }?.getOrNull(1)
