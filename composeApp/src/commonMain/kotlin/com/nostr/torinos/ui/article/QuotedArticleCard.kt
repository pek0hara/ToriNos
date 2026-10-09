package com.nostr.torinos.ui.article

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nostr.torinos.model.ArticleItem
import com.nostr.torinos.model.NostrAddressReference
import com.nostr.torinos.network.ProfileFetchPolicy
import com.nostr.torinos.network.ProfileRepository
import com.nostr.torinos.ui.components.LocalArticleNavigator
import com.nostr.torinos.ui.components.ProfileNameText
import com.nostr.torinos.ui.profile.AvatarCircle
import com.nostr.torinos.util.BoundedLruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 投稿が naddr で引用した記事の読み込み状態。 */
internal sealed interface QuotedArticleState {
    data object Loading : QuotedArticleState
    data class Loaded(val article: ArticleItem) : QuotedArticleState
    data object NotFound : QuotedArticleState
}

/**
 * 引用された記事をアドレスごとに1回だけ取得して共有する。同じ記事が複数の投稿に出ても取り直さない。
 * 記事画面で読み込んだ記事（[ArticleMemoryCache]）があれば通信しない。
 * 取得は naddr のリレーを先に試し、見つからなければ有効な全リレーから探す。
 */
internal object QuotedArticleStore {
    // UI スレッドからだけ触る。取得結果は StateFlow の値として書き戻す。
    private val states = BoundedLruCache<String, MutableStateFlow<QuotedArticleState>>(MaximumEntries)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun state(reference: NostrAddressReference): StateFlow<QuotedArticleState> {
        states[reference.address]?.let { return it }
        val cached = (reference.relayUrls + listOf(null)).firstNotNullOfOrNull { relay ->
            ArticleMemoryCache.article(relay, reference.pubkey, reference.identifier)
        }
        val flow = MutableStateFlow(cached?.let(QuotedArticleState::Loaded) ?: QuotedArticleState.Loading)
        states[reference.address] = flow
        if (cached == null) fetch(reference, flow)
        return flow
    }

    /** 見つからなかった記事を取り直す。 */
    fun retry(reference: NostrAddressReference) {
        val flow = states[reference.address] ?: return
        if (flow.value != QuotedArticleState.NotFound) return
        flow.value = QuotedArticleState.Loading
        fetch(reference, flow)
    }

    private fun fetch(reference: NostrAddressReference, flow: MutableStateFlow<QuotedArticleState>) {
        scope.launch {
            val article = try {
                reference.relayUrls.firstOrNull()?.let { relay ->
                    fetchLatestArticleByAddress(reference.pubkey, reference.identifier, relay)
                } ?: fetchLatestArticleByAddress(reference.pubkey, reference.identifier, null)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }
            flow.value = article?.let(QuotedArticleState::Loaded) ?: QuotedArticleState.NotFound
        }
    }

    private const val MaximumEntries = 200
}

/**
 * 投稿の中の記事引用。投稿の引用と同じ枠で、記事のタイトル・概要・作成者を出す。
 * タップで記事を開き、読み込めなかったときはタップで取り直す。
 */
@Composable
internal fun QuotedArticleCard(reference: NostrAddressReference) {
    val state by remember(reference.address) { QuotedArticleStore.state(reference) }.collectAsState()
    val openArticle = LocalArticleNavigator.current
    val profile by remember(reference.pubkey) { ProfileRepository.observe(reference.pubkey) }
        .collectAsState(initial = ProfileRepository.getCached(reference.pubkey))
    LaunchedEffect(reference.pubkey) {
        runCatching {
            ProfileRepository.ensureProfiles(
                setOf(reference.pubkey),
                ProfileFetchPolicy.CacheFirst(QuotedArticleProfileMaxAgeMs),
                reference.relayUrls.firstOrNull(),
            )
        }
    }
    val current = state
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .clip(MaterialTheme.shapes.small)
            .clickable(
                enabled = current is QuotedArticleState.NotFound ||
                    (current is QuotedArticleState.Loaded && openArticle != null),
            ) {
                when (current) {
                    is QuotedArticleState.Loaded -> openArticle?.invoke(reference.pubkey, reference.identifier)
                    QuotedArticleState.NotFound -> QuotedArticleStore.retry(reference)
                    QuotedArticleState.Loading -> Unit
                }
            }
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AvatarCircle(
                pubkey = reference.pubkey,
                name = profile?.bestName,
                pictureUrl = profile?.picture,
                size = 24,
            )
            ProfileNameText(
                profile = profile,
                fallback = reference.pubkey.take(8),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.AutoMirrored.Filled.Article,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("記事", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when (current) {
            QuotedArticleState.Loading -> Text(
                "記事を読み込んでいます",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            QuotedArticleState.NotFound -> Text(
                "記事を読み込めませんでした（タップで再試行）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is QuotedArticleState.Loaded -> {
                Text(
                    current.article.displayTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (current.article.displaySummary.isNotBlank()) {
                    Text(
                        current.article.displaySummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private const val QuotedArticleProfileMaxAgeMs = 15 * 60 * 1_000L
