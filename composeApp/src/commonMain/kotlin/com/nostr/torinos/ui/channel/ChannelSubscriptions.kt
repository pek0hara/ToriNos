package com.nostr.torinos.ui.channel

import com.nostr.torinos.model.NostrFilter

/**
 * チャンネル画面の購読ID。`max_subid_length`が小さいリレー（50文字など）に拒否されないよう、
 * チャンネルIDの先頭とセッション・リレーのハッシュで短く作る。
 */
internal class ChannelSubscriptionIds(channelId: String, sessionKey: String, relayKey: String) {
    private val key = "${channelId.take(12)}-${"$sessionKey|$relayKey".hashCode().toUInt().toString(36)}"
    val meta = "ch-meta-$key"
    val messages = "ch-msg-$key"
    /** 有限取得ごとに `-連番` を付けて使う。 */
    val history = "ch-hist-$key"
    val engagement = "ch-eng-$key"
    val ownHides = "ch-hide-$key"
}

/** kind 40（作成）と kind 41（メタデータ更新）。41 が先に届いても候補として持ち、40 の受信時に解決する。 */
internal fun channelMetaFilters(channelId: String): List<NostrFilter> = listOf(
    NostrFilter(ids = listOf(channelId)),
    NostrFilter(kinds = listOf(41), eTags = listOf(channelId)),
)

/** 表示中のメッセージへの返信・リアクション・リポスト・引用リポストを1つの購読で取る。 */
internal fun channelEngagementFilters(eventIds: List<String>, messageKind: Int): List<NostrFilter> = listOf(
    NostrFilter(kinds = listOf(messageKind), eTags = eventIds, limit = ENGAGEMENT_LIMIT),
    NostrFilter(kinds = listOf(7), eTags = eventIds, limit = ENGAGEMENT_LIMIT),
    NostrFilter(kinds = listOf(6, 16), eTags = eventIds, limit = ENGAGEMENT_LIMIT),
    NostrFilter(kinds = listOf(messageKind), qTags = eventIds, limit = ENGAGEMENT_LIMIT),
)

/** 自分の Hide Message（kind 43）と、それを取り消す自分の kind 5。kind 43 はチャンネルIDを持たないため自分の分をまとめて取る。 */
internal fun channelOwnHideFilters(ownPubkey: String): List<NostrFilter> = listOf(
    NostrFilter(kinds = listOf(ChannelHiddenMessages.HIDE_KIND), authors = listOf(ownPubkey), limit = OWN_HIDE_LIMIT),
    NostrFilter(
        kinds = listOf(ChannelHiddenMessages.DELETION_KIND),
        authors = listOf(ownPubkey),
        parentKindTags = listOf(ChannelHiddenMessages.HIDE_KIND.toString()),
        limit = OWN_HIDE_LIMIT,
    ),
)

private const val ENGAGEMENT_LIMIT = 500
private const val OWN_HIDE_LIMIT = 500
