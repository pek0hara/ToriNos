package com.nostr.torinos.ui.components

import com.nostr.torinos.model.*


import androidx.compose.runtime.compositionLocalOf
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrProfile

val LocalQuotePostHandler = compositionLocalOf<((NostrEvent) -> Unit)?> { null }

/** 引用した記事カードのタップで記事を開く。null なら記事カードはタップできない。 */
val LocalArticleNavigator = compositionLocalOf<((pubkey: String, identifier: String) -> Unit)?> { null }


data class QuotedEvent(
    val event: NostrEvent,
    val profile: NostrProfile?,
)

internal fun shortNotePubkey(pubkey: String): String = pubkey.take(8) + "…" + pubkey.takeLast(8)
