package com.nostr.torinos.status

data class StatusSnapshot(
    val latestByAddress: Map<StatusAddress, StatusEntry> = emptyMap(),
)

object StatusEventReducer {
    fun reduce(snapshot: StatusSnapshot, candidate: StatusEntry): StatusSnapshot {
        val current = snapshot.latestByAddress[candidate.address]
        if (current != null && !isNewerStatusEvent(candidate.event, current.event)) return snapshot
        return snapshot.copy(
            latestByAddress = snapshot.latestByAddress + (candidate.address to candidate),
        )
    }

    fun activeStatuses(
        snapshot: StatusSnapshot,
        nowEpochSeconds: Long,
        mutedPubkeys: Set<String> = emptySet(),
    ): List<StatusEntry> = snapshot.latestByAddress.values
        .asSequence()
        .filter { it.isActiveAt(nowEpochSeconds) }
        .filter { it.address.pubkey !in mutedPubkeys }
        .sortedWith(
            compareByDescending<StatusEntry> { it.event.createdAt }
                .thenBy { it.event.id },
        )
        .toList()

    fun activeStatus(
        snapshot: StatusSnapshot,
        address: StatusAddress,
        nowEpochSeconds: Long,
    ): StatusEntry? = snapshot.latestByAddress[address]
        ?.takeIf { it.isActiveAt(nowEpochSeconds) }

    fun visibleStatuses(
        snapshot: StatusSnapshot,
        nowEpochSeconds: Long,
        selectedCategories: Set<String>,
        mutedPubkeys: Set<String> = emptySet(),
    ): List<StatusEntry> {
        if (selectedCategories.isEmpty()) return emptyList()
        return activeStatuses(snapshot, nowEpochSeconds, mutedPubkeys)
            .filter { it.identifier in selectedCategories }
    }

    private fun StatusEntry.isActiveAt(nowEpochSeconds: Long): Boolean =
        content.isNotBlank() && (expiration == null || expiration > nowEpochSeconds)
}
