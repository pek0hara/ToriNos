package com.nostr.torinos.ui.article

import com.nostr.torinos.account.AccountSession
import com.nostr.torinos.account.AccountSessions
import com.nostr.torinos.model.NIP23_ARTICLE_KIND
import com.nostr.torinos.network.ImageUploader
import com.nostr.torinos.network.NostrRepository
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.ui.SafeViewModel
import com.nostr.torinos.util.appLog
import kotlin.random.Random
import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PublishedArticle(
    val pubkey: String,
    val identifier: String,
)

data class EditingArticle(
    val pubkey: String,
    val identifier: String,
    val publishedAt: Long?,
)

data class ArticleEditorState(
    val title: String = "",
    val summary: String = "",
    val content: String = "",
    val coverImageUrl: String = "",
    val topicsInput: String = "",
    val initialTitle: String = "",
    val initialSummary: String = "",
    val initialContent: String = "",
    val initialCoverImageUrl: String = "",
    val initialTopicsInput: String = "",
    val editingArticle: EditingArticle? = null,
    val isLoadingArticle: Boolean = false,
    val isUploadingCover: Boolean = false,
    val isUploadingBodyImage: Boolean = false,
    val isPublishing: Boolean = false,
    val error: String? = null,
    val publishedArticle: PublishedArticle? = null,
) {
    val isEditing: Boolean get() = editingArticle != null
    val hasUnsavedChanges: Boolean get() =
        if (isEditing) {
            title != initialTitle ||
                summary != initialSummary ||
                content != initialContent ||
                coverImageUrl != initialCoverImageUrl ||
                topicsInput != initialTopicsInput
        } else {
            title.isNotBlank() ||
                summary.isNotBlank() ||
                content.isNotBlank() ||
                coverImageUrl.isNotBlank() ||
                topicsInput.isNotBlank()
        }
    val canPublish: Boolean get() =
        title.isNotBlank() &&
            content.isNotBlank() &&
            !isLoadingArticle &&
            !isUploadingCover &&
            !isUploadingBodyImage &&
            !isPublishing
}

class ArticleEditorViewModel(
    private val editPubkey: String? = null,
    private val editIdentifier: String? = null,
    private val relayUrl: String? = null,
    private val accountSession: AccountSession? = AccountSessions.manager.currentSession,
) : SafeViewModel() {
    private val _state = MutableStateFlow(ArticleEditorState())
    val state: StateFlow<ArticleEditorState> = _state.asStateFlow()

    init {
        if (editPubkey != null && editIdentifier != null) {
            loadEditableArticle(editPubkey, editIdentifier)
        }
    }

    private fun loadEditableArticle(pubkey: String, identifier: String) {
        _state.update { it.copy(isLoadingArticle = true, error = null) }
        launch {
            runCatching {
                fetchLatestArticleByAddress(pubkey, identifier, relayUrl)
                    ?: error("編集する記事が見つかりません")
            }.onSuccess { article ->
                val topicsInput = article.meta.topics.joinToString(" ") { "#$it" }
                _state.update {
                    it.copy(
                        title = article.meta.title.orEmpty(),
                        summary = article.meta.summary.orEmpty(),
                        content = article.event.content,
                        coverImageUrl = article.meta.imageUrl.orEmpty(),
                        topicsInput = topicsInput,
                        initialTitle = article.meta.title.orEmpty(),
                        initialSummary = article.meta.summary.orEmpty(),
                        initialContent = article.event.content,
                        initialCoverImageUrl = article.meta.imageUrl.orEmpty(),
                        initialTopicsInput = topicsInput,
                        editingArticle = EditingArticle(
                            pubkey = article.event.pubkey,
                            identifier = article.meta.identifier,
                            publishedAt = article.meta.publishedAt,
                        ),
                        isLoadingArticle = false,
                        error = null,
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        isLoadingArticle = false,
                        error = error.message ?: "記事を読み込めませんでした",
                    )
                }
            }
        }
    }

    fun onTitleChange(value: String) {
        if (value.length <= ARTICLE_TITLE_MAX_LENGTH) {
            _state.update { it.copy(title = value, error = null) }
        }
    }

    fun onSummaryChange(value: String) {
        if (value.length <= ARTICLE_SUMMARY_MAX_LENGTH) {
            _state.update { it.copy(summary = value, error = null) }
        }
    }

    fun onContentChange(value: String) {
        if (value.length <= ARTICLE_CONTENT_MAX_LENGTH) {
            _state.update { it.copy(content = value, error = null) }
        }
    }

    fun onCoverImageUrlChange(value: String) {
        _state.update { it.copy(coverImageUrl = value, error = null) }
    }

    fun onTopicsChange(value: String) {
        if (value.length <= ARTICLE_TOPICS_INPUT_MAX_LENGTH) {
            _state.update { it.copy(topicsInput = value, error = null) }
        }
    }

    fun uploadCoverImage(bytes: ByteArray, mimeType: String) {
        if (_state.value.isUploadingCover) return
        _state.update { it.copy(isUploadingCover = true, error = null) }
        launch {
            ImageUploader.upload(bytes, mimeType, accountSession?.signer)
                .onSuccess { url ->
                    _state.update {
                        it.copy(
                            coverImageUrl = url,
                            isUploadingCover = false,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            isUploadingCover = false,
                            error = "カバー画像のアップロードに失敗しました: ${error.message}",
                        )
                    }
                }
        }
    }

    fun uploadBodyImage(bytes: ByteArray, mimeType: String) {
        if (_state.value.isUploadingBodyImage) return
        _state.update { it.copy(isUploadingBodyImage = true, error = null) }
        launch {
            ImageUploader.upload(bytes, mimeType, accountSession?.signer)
                .onSuccess { url ->
                    _state.update {
                        val separator = if (it.content.isBlank()) "" else "\n\n"
                        val markdown = "![画像]($url)"
                        val updatedContent = it.content + separator + markdown
                        if (updatedContent.length > ARTICLE_CONTENT_MAX_LENGTH) {
                            it.copy(
                                isUploadingBodyImage = false,
                                error = "本文の文字数上限を超えるため画像を追加できません",
                            )
                        } else {
                            it.copy(
                                content = updatedContent,
                                isUploadingBodyImage = false,
                            )
                        }
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            isUploadingBodyImage = false,
                            error = "本文画像のアップロードに失敗しました: ${error.message}",
                        )
                    }
                }
        }
    }

    fun publish() {
        val current = _state.value
        if (!current.canPublish) return

        val coverUrl = current.coverImageUrl.trim()
        if (coverUrl.isNotEmpty() && !isHttpsUrl(coverUrl)) {
            _state.update { it.copy(error = "カバー画像にはHTTPS URLを指定してください") }
            return
        }
        val relayUrls = RelayStore.enabledRelayUrlsSnapshot()
        if (relayUrls.isEmpty()) {
            _state.update { it.copy(error = "投稿先リレーが設定されていません") }
            return
        }
        if (containsHtml(current.content)) {
            _state.update { it.copy(error = "記事本文にHTMLは使用できません") }
            return
        }

        _state.update { it.copy(isPublishing = true, error = null) }
        launch {
            appLog("[ArticleEditor] publish started relays=${relayUrls.size}")
            runCatching {
                val signer = accountSession?.signer ?: error("秘密鍵が設定されていません")
                val now = Clock.System.now().epochSeconds
                val editingArticle = current.editingArticle
                val publishedAt = editingArticle?.publishedAt ?: now
                val identifier = editingArticle?.identifier ?: newArticleIdentifier(now)
                val tags = buildArticleTags(
                    identifier = identifier,
                    title = current.title,
                    summary = current.summary,
                    coverImageUrl = coverUrl,
                    topicsInput = current.topicsInput,
                    publishedAt = publishedAt,
                )
                val event = signer.sign(
                    content = current.content.trim(),
                    kind = NIP23_ARTICLE_KIND,
                    tags = tags,
                )
                if (editingArticle != null && event.pubkey != editingArticle.pubkey) {
                    error("この記事を投稿したアカウントでログインしてください")
                }
                NostrRepository.publishToRelays(event, relayUrls)
                ArticleMemoryCache.publishArticle(event, relayUrls)
                PublishedArticle(event.pubkey, identifier)
            }.onSuccess { article ->
                appLog("[ArticleEditor] publish accepted identifier=${article.identifier}")
                _state.update {
                    it.copy(
                        isPublishing = false,
                        publishedArticle = article,
                    )
                }
            }.onFailure { error ->
                appLog("[ArticleEditor] publish failed: ${error::class.simpleName}: ${error.message}")
                _state.update {
                    it.copy(
                        isPublishing = false,
                        error = error.message ?: "記事の投稿に失敗しました",
                    )
                }
            }
        }
    }
}

internal fun buildArticleTags(
    identifier: String,
    title: String,
    summary: String,
    coverImageUrl: String,
    topicsInput: String,
    publishedAt: Long,
): List<List<String>> = buildList {
    add(listOf("d", identifier))
    add(listOf("title", title.trim()))
    summary.trim().takeIf { it.isNotEmpty() }?.let { add(listOf("summary", it)) }
    coverImageUrl.trim().takeIf { it.isNotEmpty() }?.let { add(listOf("image", it)) }
    add(listOf("published_at", publishedAt.toString()))
    parseArticleTopics(topicsInput).forEach { add(listOf("t", it)) }
    add(listOf("client", "ToriNos"))
}

internal fun parseArticleTopics(value: String): List<String> =
    value
        .split(Regex("""[\s,、]+"""))
        .map { it.trim().removePrefix("#") }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(ARTICLE_TOPICS_MAX_COUNT)

internal fun containsHtml(value: String): Boolean =
    HTML_TAG_REGEX.containsMatchIn(value)

private fun newArticleIdentifier(epochSeconds: Long): String =
    "torinos-$epochSeconds-${Random.nextLong().toULong().toString(16)}"

private fun isHttpsUrl(value: String): Boolean =
    value.startsWith("https://", ignoreCase = true)

private val HTML_TAG_REGEX = Regex(
    pattern = """<\s*/?\s*[a-zA-Z][^>]*>""",
)

internal const val ARTICLE_TITLE_MAX_LENGTH = 200
internal const val ARTICLE_SUMMARY_MAX_LENGTH = 500
internal const val ARTICLE_CONTENT_MAX_LENGTH = 100_000
private const val ARTICLE_TOPICS_INPUT_MAX_LENGTH = 500
private const val ARTICLE_TOPICS_MAX_COUNT = 20
