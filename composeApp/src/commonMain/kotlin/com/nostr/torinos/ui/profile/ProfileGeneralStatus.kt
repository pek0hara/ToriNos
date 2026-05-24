package com.nostr.torinos.ui.profile

import com.nostr.torinos.model.NostrEvent
import kotlin.time.Clock

internal const val PROFILE_STATUS_KIND = 30315
internal const val PROFILE_GENERAL_STATUS_TAG = "general"

internal fun NostrEvent.toActiveGeneralStatusContent(): String? {
    if (kind != PROFILE_STATUS_KIND) return null
    val statusTag = tags.firstOrNull { it.firstOrNull() == "d" }
        ?.getOrNull(1)
        ?.takeIf { it.isNotBlank() }
        ?: PROFILE_GENERAL_STATUS_TAG
    if (statusTag != PROFILE_GENERAL_STATUS_TAG) return null

    val expiration = tags.firstOrNull { it.firstOrNull() == "expiration" }
        ?.getOrNull(1)
        ?.toLongOrNull()
    if (expiration != null && expiration <= Clock.System.now().epochSeconds) return null

    return content.trim().takeIf { it.isNotBlank() }
}
