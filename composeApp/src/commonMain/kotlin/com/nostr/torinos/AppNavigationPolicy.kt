package com.nostr.torinos

internal data class FooterNavigationPlan(
    val rootToReveal: String?,
    val clearOrphanPages: Boolean,
    val navigate: Boolean,
    val resetFeedChrome: Boolean,
)

internal fun footerNavigationPlan(root: String?, destination: String) = FooterNavigationPlan(
    rootToReveal = root,
    clearOrphanPages = root == null,
    navigate = root != destination,
    resetFeedChrome = destination == "feed",
)
