package com.nostr.torinos.ui.timeline

import com.nostr.torinos.engagement.NoteEngagementState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** 画面をまたいで、同じ投稿カードの表示値を引き継ぐための一時的な同期情報。 */
internal data class NoteCardSnapshot(
    val sessionId: String?,
    val eventId: String,
    val replyCount: Int?,
    val engagement: NoteEngagementState?,
)

internal object NoteCardSync {
    private val mutableUpdates = MutableSharedFlow<NoteCardSnapshot>(extraBufferCapacity = 32)
    val updates: SharedFlow<NoteCardSnapshot> = mutableUpdates.asSharedFlow()

    fun publish(snapshot: NoteCardSnapshot) {
        mutableUpdates.tryEmit(snapshot)
    }
}
