package com.nostr.torinos.ui.article

import com.nostr.torinos.article.ArticleReactor
import com.nostr.torinos.engagement.NoteEngagementState
import com.nostr.torinos.model.ReactionOption
import kotlin.test.Test
import kotlin.test.assertEquals

class ArticleReactorMergeTest {
    private val fire = ReactionOption.Unicode("🔥")

    @Test fun justSentOwnReactionAppearsBeforeTheNextFetch() {
        val fetched = listOf(ArticleReactor("a", "alice", null, 100))
        val state = NoteEngagementState(ownLikeEventId = "mine", ownEmojiReactionEventIds = mapOf(fire.key to "mine-fire"))
        val merged = withOwnReactions(fetched, state, "me", nowSeconds = 200)
        assertEquals(listOf("mine", "mine-fire", "a"), merged.map { it.eventId })
        assertEquals(listOf(null, fire, null), merged.map { it.option })
    }

    @Test fun withdrawnOwnReactionDisappearsAndFetchedOwnKeepsItsTime() {
        val fetched = listOf(
            ArticleReactor("old-like", "me", null, 150),
            ArticleReactor("fire", "me", fire, 120),
            ArticleReactor("a", "alice", null, 100),
        )
        val state = NoteEngagementState(ownEmojiReactionEventIds = mapOf(fire.key to "fire"))
        assertEquals(listOf("fire", "a"), withOwnReactions(fetched, state, "me", nowSeconds = 999).map { it.eventId })
        assertEquals(fetched, withOwnReactions(fetched, state, null))
    }
}
