package com.nostr.torinos.ui.article

import com.nostr.torinos.model.ReactionOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArticleEngagementBarTest {
    @Test
    fun reactionOptionFromKey_restoresUnicodeAndCustomOptions() {
        val unicode = ReactionOption.Unicode("🔥")
        val custom = ReactionOption.Custom("party", "https://example.com/party.png")

        assertEquals(unicode, reactionOptionFromKey(unicode.key))
        assertEquals(custom, reactionOptionFromKey(custom.key))
        assertNull(reactionOptionFromKey("like"))
    }
}
