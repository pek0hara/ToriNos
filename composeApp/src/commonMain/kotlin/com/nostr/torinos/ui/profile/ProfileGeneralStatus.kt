package com.nostr.torinos.ui.profile

import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.status.GENERAL_STATUS_IDENTIFIER
import com.nostr.torinos.status.STATUS_EVENT_KIND
import com.nostr.torinos.status.StatusAddress
import com.nostr.torinos.status.StatusEntry
import com.nostr.torinos.status.StatusEventCodec
import com.nostr.torinos.status.StatusEventReducer
import com.nostr.torinos.status.StatusSnapshot
import kotlin.time.Clock

internal const val PROFILE_STATUS_KIND = STATUS_EVENT_KIND
internal const val PROFILE_GENERAL_STATUS_TAG = GENERAL_STATUS_IDENTIFIER

data class ProfileGeneralStatus(
    val content: String,
    val expiration: Long? = null,
    val referenceUrl: String? = null,
    val customEmojis: Map<String, String> = emptyMap(),
)

internal data class ProfileGeneralStatusReduction(
    val snapshot: StatusSnapshot,
    val generalStatus: ProfileGeneralStatus?,
)

internal fun reduceProfileGeneralStatus(
    snapshot: StatusSnapshot,
    event: NostrEvent,
    expectedPubkey: String,
    nowEpochSeconds: Long = Clock.System.now().epochSeconds,
): ProfileGeneralStatusReduction? {
    val candidate = StatusEventCodec.parse(event) ?: return null
    val expectedAddress = StatusAddress(expectedPubkey, GENERAL_STATUS_IDENTIFIER)
    if (candidate.address != expectedAddress) return null
    val updated = StatusEventReducer.reduce(snapshot, candidate)
    if (updated == snapshot) return null
    return ProfileGeneralStatusReduction(
        snapshot = updated,
        generalStatus = StatusEventReducer.activeStatus(updated, expectedAddress, nowEpochSeconds)
            ?.toProfileGeneralStatus(),
    )
}

internal fun NostrEvent.toActiveGeneralStatus(
    nowEpochSeconds: Long = Clock.System.now().epochSeconds,
): ProfileGeneralStatus? {
    val status = StatusEventCodec.parse(this) ?: return null
    if (status.identifier != PROFILE_GENERAL_STATUS_TAG) return null
    val expiration = status.expiration
    if (expiration != null && expiration <= nowEpochSeconds) return null
    return status.toProfileGeneralStatus()
}

private fun StatusEntry.toProfileGeneralStatus(): ProfileGeneralStatus? {
    val body = content.trim().takeIf { it.isNotBlank() } ?: return null
    return ProfileGeneralStatus(
        content = body,
        expiration = expiration,
        referenceUrl = referenceUrls.firstOrNull(),
        customEmojis = customEmojis,
    )
}
