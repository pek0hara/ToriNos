package com.nostr.torinos.ui.feed

internal const val FEED_HARD_RESET_BACKGROUND_MILLIS = 10L * 60L * 1_000L

internal fun shouldResetFeedAfterBackground(
    backgroundedAtMillis: Long,
    foregroundedAtMillis: Long,
): Boolean =
    foregroundedAtMillis - backgroundedAtMillis > FEED_HARD_RESET_BACKGROUND_MILLIS
