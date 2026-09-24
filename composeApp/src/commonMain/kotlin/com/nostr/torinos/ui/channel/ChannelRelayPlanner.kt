package com.nostr.torinos.ui.channel

import com.nostr.torinos.model.ChannelRelayContext
import com.nostr.torinos.model.ChannelRelayContextBuilder
import com.nostr.torinos.network.RelayEntry
import com.nostr.torinos.network.RelayTarget
import com.nostr.torinos.network.normalizeRelayUrl
import com.nostr.torinos.network.readableRelayUrls
import com.nostr.torinos.network.writableRelayUrls

/** チャンネル画面の relay context と購読先の決定(第16.5〜16.7節)。副作用を持たない。 */
internal object ChannelRelayPlanner {
    fun context(
        recommendedRelays: List<String>,
        navigationRelayHint: String?,
        relayEntries: List<RelayEntry>,
    ): ChannelRelayContext = ChannelRelayContextBuilder.build(
        recommendedRelays = recommendedRelays,
        navigationRelayHint = navigationRelayHint,
        userReadRelays = readableRelayUrls(relayEntries),
        userWriteRelays = writableRelayUrls(relayEntries),
        // ユーザー設定で write を明示的に切った URL だけを配送先から外す。未登録 URL は許可する。
        nonWritableRelays = relayEntries.filterNot { it.write }.map { it.url },
    )

    /**
     * 購読先。推奨リレーもユーザーリレーも無い場合だけ従来の既定動作へ戻す。
     * [RelayTarget.Explicit] は未登録の推奨リレーにも一時接続する(第16.6節)。
     */
    fun readTarget(readRelays: Set<String>, navigationRelayHint: String?): RelayTarget = when {
        readRelays.isNotEmpty() -> RelayTarget.Explicit(readRelays)
        navigationRelayHint != null -> RelayTarget.Single(navigationRelayHint)
        else -> RelayTarget.AllEnabled
    }

    /**
     * kind 41 で read relay が old から next に変わるときの二段階更新(第16.7節)。
     * [transitionTargets] で新旧両方を購読し、[addedRelays] の準備完了または timeout 後に next へ縮める。
     * 追加が無ければ一段階で next へ移ってよい。
     */
    data class Transition(
        val transitionTargets: Set<String>,
        val addedRelays: Set<String>,
        val finalTargets: Set<String>,
    ) {
        val needsWarmUp: Boolean get() = addedRelays.isNotEmpty()
    }

    fun transition(old: ChannelRelayContext, next: ChannelRelayContext): Transition = Transition(
        transitionTargets = old.readRelays + next.readRelays,
        addedRelays = next.readRelays - old.readRelays,
        finalTargets = next.readRelays,
    )

    fun normalizeHint(relayUrl: String?): String? = relayUrl?.let(::normalizeRelayUrl)
}
