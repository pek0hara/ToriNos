package com.nostr.torinos.badge

/** Pure state for the badge sheet (BadgeSheet.kt), kept apart from Compose so it can be unit tested. */
internal enum class BadgeSheetTab(val label: String) { Displayed("表示中"), Received("受け取った"), Issued("発行した") }

internal enum class BadgeNoticeTone { Info, Warning, Error }

internal enum class BadgeNoticeAction(val label: String) {
    ReloadDraft("最新を読む"),
    Retry("再試行"),
    ResolveMore("再取得"),
}

/** The one banner shown under the tabs. */
internal data class BadgeNotice(val tone: BadgeNoticeTone, val text: String, val action: BadgeNoticeAction?)

/** Short label for the display list's sync state, shown as a chip instead of a banner. */
internal fun badgeSyncLabel(message: String?): String? = when (message) {
    BADGE_MESSAGE_PENDING -> "送信待ち"
    BADGE_MESSAGE_PARTIAL_SYNC -> "一部未同期"
    else -> null
}

private val nonErrorMessages = setOf(
    BADGE_MESSAGE_PENDING, BADGE_MESSAGE_PARTIAL_SYNC, BADGE_MESSAGE_SAVED, BADGE_MESSAGE_CONFLICT,
    BADGE_MESSAGE_STALE_DRAFT, BADGE_MESSAGE_NO_RELAYS, BADGE_MESSAGE_AWARDS_PARTIAL,
)

/**
 * Picks the banner for [tab]; only one is shown, by priority: conflict, error, unsent, partial fetch.
 * [message] is AccountBadgeStore's message, [localError] a failure caught by the sheet itself, and
 * [draftStale] whether the draft being edited was started from an older display list than the current one.
 */
internal fun badgeSheetNotice(
    tab: BadgeSheetTab,
    message: String? = null,
    localError: String? = null,
    profileError: String? = null,
    profilePartial: Boolean = false,
    awardsIncomplete: Boolean = false,
    draftStale: Boolean = false,
    issuerMessage: String? = null,
    issuerIncomplete: Boolean = false,
): BadgeNotice? {
    if (tab == BadgeSheetTab.Issued) return when {
        issuerMessage != null -> BadgeNotice(BadgeNoticeTone.Error, issuerMessage, BadgeNoticeAction.Retry)
        issuerIncomplete -> BadgeNotice(BadgeNoticeTone.Info, "発行したバッジや授与履歴を一部取得できていません", BadgeNoticeAction.Retry)
        else -> null
    }
    val error = localError ?: message?.takeIf { it !in nonErrorMessages } ?: profileError
    return when {
        // A rejected save leaves STALE_DRAFT behind; it only matters while the draft is still stale.
        draftStale -> BadgeNotice(BadgeNoticeTone.Warning,
            "別の端末で表示バッジが更新されました。最新を読むと、編集中の内容は破棄されます", BadgeNoticeAction.ReloadDraft)
        message == BADGE_MESSAGE_CONFLICT -> BadgeNotice(BadgeNoticeTone.Warning,
            "別の端末で表示バッジが更新されたため、この端末の未送信の変更は送らずに止めています。いまの内容で保存し直すと解消します", null)
        error != null -> BadgeNotice(BadgeNoticeTone.Error, error, BadgeNoticeAction.Retry)
        message == BADGE_MESSAGE_NO_RELAYS -> BadgeNotice(BadgeNoticeTone.Warning,
            "書き込み先のリレーがないため、表示バッジを送信できません。リレー設定を確認してください", null)
        tab == BadgeSheetTab.Displayed && profilePartial ->
            BadgeNotice(BadgeNoticeTone.Info, "一部のバッジ情報を取得できていません", BadgeNoticeAction.ResolveMore)
        tab == BadgeSheetTab.Received && (awardsIncomplete || message == BADGE_MESSAGE_AWARDS_PARTIAL) ->
            BadgeNotice(BadgeNoticeTone.Info, "受け取ったバッジを一部取得できていません", BadgeNoticeAction.Retry)
        else -> null
    }
}

/** Display items as saved, which the draft is compared against. */
internal fun BadgeDraft.baselineItems(): List<BadgeSelectionItem> = BadgeSelection.parse(source?.tags.orEmpty()).items

internal fun BadgeDraft.isDirty(): Boolean = items != baselineItems()

internal fun BadgeDraft.moved(from: Int, to: Int): BadgeDraft {
    if (from !in items.indices || to !in items.indices || from == to) return this
    val next = items.toMutableList()
    next.add(to, next.removeAt(from))
    return copy(items = next)
}

internal fun BadgeDraft.removedAt(index: Int): BadgeDraft =
    if (index !in items.indices) this else copy(items = items.filterIndexed { i, _ -> i != index })

internal enum class BadgeShownState { Hidden, Shown, ShownViaSet }

/**
 * Whether [address] is on the display list. [expanded] is the list with badge sets (kind 30008)
 * opened up; a badge that is shown only through a set cannot be removed on its own.
 */
internal fun badgeShownState(items: List<BadgeSelectionItem>, expanded: List<BadgeSelectionItem>, address: String): BadgeShownState = when {
    items.any { it.address?.kind == 30009 && it.address?.value == address } -> BadgeShownState.Shown
    expanded.any { it.address?.value == address } -> BadgeShownState.ShownViaSet
    else -> BadgeShownState.Hidden
}

/** Adds the badge at the end, or removes it when it is listed directly. */
internal fun BadgeDraft.toggled(address: String, item: () -> BadgeSelectionItem): BadgeDraft =
    if (items.any { it.address?.kind == 30009 && it.address?.value == address })
        copy(items = items.filterNot { it.address?.kind == 30009 && it.address?.value == address })
    else copy(items = items + item())

/** Stable, unique keys for the rows; a list may repeat the same pair. */
internal fun badgeRowKeys(items: List<BadgeSelectionItem>): List<String> {
    val seen = mutableMapOf<String, Int>()
    return items.map { item ->
        val count = seen[item.key] ?: 0
        seen[item.key] = count + 1
        "${item.key}#$count"
    }
}

/**
 * One drag step for a list of equal-height rows: a row moves to its neighbour's slot once it has
 * been dragged past half a row. Returns the new index and the offset left relative to that slot.
 */
internal fun badgeDragStep(index: Int, lastIndex: Int, offset: Float, rowHeight: Float): Pair<Int, Float> {
    if (rowHeight <= 0f) return index to offset
    var target = index
    var rest = offset
    while (rest > rowHeight / 2 && target < lastIndex) { target++; rest -= rowHeight }
    while (rest < -rowHeight / 2 && target > 0) { target--; rest += rowHeight }
    // At either end the row stops instead of being dragged out of the list.
    return target to rest.coerceIn(if (target == 0) 0f else -rowHeight / 2, if (target >= lastIndex) 0f else rowHeight / 2)
}
