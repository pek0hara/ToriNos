package com.nostr.torinos.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.nostr.torinos.account.LocalAccountSession
import com.nostr.torinos.network.DisplayPreferencesStore

/** 設定・アカウントの接続はHostで行い、描画部品は表示入力を受け取る。 */
@Composable
internal fun NoteCardHost(model: NoteCardModel, actions: NoteCardActions) {
    val showImages by DisplayPreferencesStore.showImagePreviews.collectAsState()
    val showX by DisplayPreferencesStore.showXPreviews.collectAsState()
    NoteCardContent(model.copy(policy = model.policy.copy(showImagePreviews = showImages, showXPreviews = showX)),
        actions, LocalAccountSession.current, LocalQuotePostHandler.current)
}
