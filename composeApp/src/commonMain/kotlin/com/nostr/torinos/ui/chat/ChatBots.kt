package com.nostr.torinos.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import com.nostr.torinos.chat.BUZZ_AGENT_MESSAGE_KIND
import com.nostr.torinos.chat.GroupMessage
import com.nostr.torinos.model.NostrProfile

/**
 * リレーから分かった bot（Buzz の役割が bot の人と、エージェントの発言 40002 を出した人）。
 * 発言の行は呼び出し元が多いので、画面ごとにここで渡す。
 */
internal val LocalChatBots = compositionLocalOf<Set<String>> { emptySet() }

/** 発言の名前の横に Bot の印を出すか。プロフィールの `bot`（NIP-24）か、リレーで bot と分かった人。 */
@Composable
internal fun isChatBot(message: GroupMessage, profile: NostrProfile?): Boolean =
    profile?.bot == true ||
        message.event.kind == BUZZ_AGENT_MESSAGE_KIND ||
        message.author in LocalChatBots.current
