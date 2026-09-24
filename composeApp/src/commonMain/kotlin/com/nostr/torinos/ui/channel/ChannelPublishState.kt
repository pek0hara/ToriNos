package com.nostr.torinos.ui.channel

import com.nostr.torinos.model.ChannelRelayContext
import com.nostr.torinos.network.RelayPublishResult
import com.nostr.torinos.ui.timeline.SignedPublishResult

/** 投稿のリレー別進行状態(第16.9節)。送信先は投稿開始時点の snapshot。 */
data class ChannelPublishUiState(
    val phase: Phase = Phase.Idle,
    val targets: List<String> = emptyList(),
    val succeeded: Set<String> = emptySet(),
    val failed: Map<String, String> = emptyMap(),
) {
    enum class Phase { Idle, Sending, PartialSuccess, Success, Failed }

    /** 一部失敗時の案内。例: "2件中1件に送信しました"。 */
    val summary: String?
        get() = when (phase) {
            Phase.PartialSuccess -> "${succeeded.size + failed.size}件中${succeeded.size}件に送信しました"
            Phase.Failed -> if (failed.isEmpty()) null else "${failed.size}件すべてのリレーへの送信に失敗しました"
            else -> null
        }

    /** 残りのリレーの結果を統合する。全送信先の結果が揃うまでは Sending のまま進捗だけ増やす。 */
    internal fun withRelayResult(result: RelayPublishResult): ChannelPublishUiState {
        if (phase == Phase.Idle) return this
        val nextSucceeded = succeeded + result.succeededRelays
        val nextFailed = (failed + result.failedRelays) - nextSucceeded
        val done = targets.isNotEmpty() && targets.all { it in nextSucceeded || it in nextFailed }
        val nextPhase = when {
            !done -> if (phase == Phase.Failed) Phase.Failed else Phase.Sending
            nextSucceeded.isEmpty() -> Phase.Failed
            nextFailed.isEmpty() -> Phase.Success
            else -> Phase.PartialSuccess
        }
        return copy(phase = nextPhase, succeeded = nextSucceeded, failed = nextFailed)
    }

    companion object {
        val Idle = ChannelPublishUiState()

        fun sending(targets: Collection<String>) = ChannelPublishUiState(Phase.Sending, targets.toList())

        internal fun from(targets: Collection<String>, result: SignedPublishResult): ChannelPublishUiState = when (result) {
            // 最初の受理で戻るため、未回答のリレーがあれば Sending のまま残りを待つ。
            is SignedPublishResult.Published -> sending(targets).withRelayResult(result.relayResult).let { state ->
                if (targets.isEmpty()) state.copy(phase = Phase.Success) else state
            }
            is SignedPublishResult.Failed -> ChannelPublishUiState(
                phase = Phase.Failed,
                targets = targets.toList(),
                failed = result.relayResult?.failedRelays.orEmpty(),
            )
            SignedPublishResult.MissingSigner -> ChannelPublishUiState(Phase.Failed, targets.toList())
        }
    }
}

/**
 * 投稿開始時点で固定する送信先と relay hint(第16.9節)。送信中に kind 41 で context が変わっても、
 * この投稿のタグと送信先は変えない。送信先が空ならユーザーの書き込みリレー(従来動作)へ任せる。
 */
internal data class ChannelPublishContext(
    val relayUrls: List<String>?,
    val primaryHint: String?,
) {
    val targetsForDisplay: List<String> get() = relayUrls.orEmpty()

    companion object {
        fun from(context: ChannelRelayContext) = ChannelPublishContext(
            relayUrls = context.writeRelays.toList().ifEmpty { null },
            primaryHint = context.primaryHint,
        )
    }
}
