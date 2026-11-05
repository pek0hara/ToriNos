package com.nostr.torinos.ui.article

import com.nostr.torinos.model.ArticleItem
import com.nostr.torinos.model.ArticleMeta
import com.nostr.torinos.model.NIP23_ARTICLE_KIND
import com.nostr.torinos.model.NostrAddressReference
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.network.RelayTarget
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class QuotedArticleLoaderTest {
    private val pubkey = "2d5b6404df532de082d9e77f7f4257a6f43fb79bb9de8dd3ac7df5e6d4b500b0"

    private fun reference(vararg relays: String) =
        NostrAddressReference(30023, pubkey, "blockbuzzbuilderlabdessa", relays.toList())

    @Test
    fun asksHintRelayOutsideSettingsWhenEnabledRelaysFail() = runTest {
        // Ditto の naddr は設定に無い relay.ditto.pub を指す。有効なリレーが応答しなくても、そこを直接問い合わせる。
        val article = sampleArticle()
        val targets = mutableListOf<RelayTarget>()
        val loaded = loadQuotedArticle(reference("wss://relay.ditto.pub/")) { target ->
            targets += target
            if (target == RelayTarget.AllEnabled) error("記事を取得できませんでした。再試行してください") else article
        }
        assertSame(article, loaded)
        assertEquals(listOf(RelayTarget.AllEnabled, RelayTarget.Explicit(setOf("wss://relay.ditto.pub/"))), targets)
    }

    @Test
    fun doesNotAskHintRelayWhenEnabledRelaysHaveArticle() = runTest {
        val article = sampleArticle()
        val targets = mutableListOf<RelayTarget>()
        val loaded = loadQuotedArticle(reference("wss://relay.ditto.pub/")) { target -> targets += target; article }
        assertSame(article, loaded)
        assertEquals(listOf<RelayTarget>(RelayTarget.AllEnabled), targets)
    }

    @Test
    fun ignoresNonWebSocketHintsAndReturnsNullWhenNothingFound() = runTest {
        val targets = mutableListOf<RelayTarget>()
        val loaded = loadQuotedArticle(reference("https://example.com")) { target -> targets += target; null }
        assertNull(loaded)
        assertEquals(listOf<RelayTarget>(RelayTarget.AllEnabled), targets)
    }

    private fun sampleArticle() = ArticleItem(
        NostrEvent("ff".repeat(32), pubkey, 1, NIP23_ARTICLE_KIND, emptyList(), "", ""),
        ArticleMeta("blockbuzzbuilderlabdessa", "t", null, null, null, emptyList()),
    )
}
