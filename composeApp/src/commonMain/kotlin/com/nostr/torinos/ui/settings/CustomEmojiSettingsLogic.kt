package com.nostr.torinos.ui.settings

import com.nostr.torinos.emoji.CustomEmoji
import com.nostr.torinos.emoji.EmojiSetAddress
import com.nostr.torinos.emoji.PublishedEmojiSet
import com.nostr.torinos.emoji.RegisteredEmojiSet
import com.nostr.torinos.emoji.normalizeEmojiSearchQuery
import com.nostr.torinos.emoji.normalizeShortcode

/** 設定画面で詳細を開く絵文字セット。登録済みか公開一覧のどちらから来たかは問わない。 */
internal data class EmojiSetView(
    val address: EmojiSetAddress,
    val title: String,
    val emojis: List<CustomEmoji>,
)

internal fun RegisteredEmojiSet.toView() = EmojiSetView(address, title, emojis)

internal fun PublishedEmojiSet.toView() = EmojiSetView(address, name, emojis)

internal fun EmojiSetView.toRegisteredSet() = RegisteredEmojiSet(address, title, emojis)

/**
 * タップした絵文字から開くセットを決める。
 *
 * 1. アドレスが分かれば、そのセット（登録済み → 公開一覧の順）。
 * 2. shortcode と URL が一致するセット（登録済み → 公開一覧の順）。
 * 3. 公開一覧の読み込みが終わっていて、shortcode だけで一致するセットがちょうど1つならそれ。
 * どれでもないか、URL が分からなければ null（検索語として一覧を表示する）。
 */
internal fun findRequestedEmojiSet(
    shortcode: String,
    imageUrl: String,
    address: EmojiSetAddress?,
    registered: List<RegisteredEmojiSet>,
    published: List<PublishedEmojiSet>,
    isPublishedLoading: Boolean,
): EmojiSetView? {
    val candidates = registered.map { it.toView() } + published.map { it.toView() }
    if (address != null) {
        return candidates.firstOrNull { it.address == address }
    }
    val code = normalizeShortcode(shortcode)
    val url = imageUrl.trim()
    // 画像の分からない未登録コード（本文中の :xxx:）は、検索語として一覧を出すだけにする。
    if (code.isBlank() || url.isBlank()) return null
    candidates.firstOrNull { set -> set.emojis.any { it.shortcode == code && it.imageUrl == url } }
        ?.let { return it }
    if (isPublishedLoading) return null
    return candidates
        .filter { set -> set.emojis.any { it.shortcode == code } }
        .distinctBy { it.address }
        .singleOrNull()
}

internal fun selectInitialEmoji(
    emojis: List<CustomEmoji>,
    initialShortcode: String,
    initialImageUrl: String,
): CustomEmoji? {
    val shortcode = normalizeShortcode(initialShortcode)
    val imageUrl = initialImageUrl.trim()
    return emojis.firstOrNull { emoji ->
        emoji.shortcode == shortcode && emoji.imageUrl == imageUrl
    } ?: emojis.firstOrNull { emoji ->
        emoji.shortcode == shortcode
    } ?: emojis.firstOrNull()
}

/**
 * 一覧に出すセットを絞り込む。
 * 「登録済みのみ」では登録済みセットをすべて出す。公開一覧にあるものはその情報を使い、
 * 無いもの（別リレーで見つけたセットなど）は登録時の内容で補う。
 */
internal fun List<PublishedEmojiSet>.filterEmojiSets(
    query: String,
    registeredOnly: Boolean,
    registered: List<RegisteredEmojiSet>,
): List<PublishedEmojiSet> {
    val normalizedQuery = normalizeEmojiSearchQuery(query)
    val source = if (registeredOnly) {
        val published = associateBy { it.address }
        registered.map { set ->
            published[set.address] ?: PublishedEmojiSet(set.address, "", set.title, 0, set.emojis)
        }
    } else {
        this
    }
    if (normalizedQuery.isBlank()) return source
    return source.filter { set ->
        set.name.lowercase().contains(normalizedQuery) ||
            set.authorPubkey.contains(normalizedQuery) ||
            set.emojis.any { it.shortcode.lowercase().contains(normalizedQuery) }
    }
}

internal enum class EmojiSetSort(val label: String) {
    Adoption("フォロー内の登録順"), Newest("新着・更新順"),
}

/** 取得中・スクロール中は既存行の相対順を保持し、新しい行だけ末尾へ追加する。 */
internal fun reconcileEmojiSetOrder(previous: List<String>, desired: List<String>, apply: Boolean): List<String> {
    if (apply) return desired
    val existing = previous.toSet()
    val visible = desired.toSet()
    return previous.filter { it in visible } + desired.filter { it !in existing }
}

internal fun emojiSetSearchRank(set: PublishedEmojiSet, query: String): Int {
    val normalized = normalizeEmojiSearchQuery(query)
    if (normalized.isBlank()) return 0
    return (listOf(set.name) + set.emojis.map { it.shortcode }).maxOf { value ->
        val text = value.lowercase()
        when { text == normalized -> 3; text.startsWith(normalized) -> 2; normalized in text -> 1; else -> 0 }
    }
}

internal fun List<PublishedEmojiSet>.sortForDiscovery(
    query: String, sort: EmojiSetSort, counts: Map<EmojiSetAddress, Int>,
): List<PublishedEmojiSet> = sortedWith(
    compareByDescending<PublishedEmojiSet> { emojiSetSearchRank(it, query) }
        .thenByDescending { if (sort == EmojiSetSort.Adoption) counts[it.address] ?: 0 else 0 }
        .thenByDescending { it.createdAt }
        .thenBy { it.name.lowercase() }
        .thenBy { it.address.value },
)

/**
 * フォロー先が登録しているが一覧に無いセットを、登録人数の多い順に [budget] 件まで選ぶ。
 * リレーが無いと答えたセットは選ばず、取得枠も使わない。
 */
internal fun emojiReferencesToRequest(
    counts: Map<EmojiSetAddress, Int>,
    published: List<PublishedEmojiSet>,
    failed: Set<EmojiSetAddress>,
    budget: Int,
): List<EmojiSetAddress> {
    val known = published.mapTo(HashSet()) { it.address }
    return counts.entries
        .filter { it.key !in known && it.key !in failed }
        .sortedWith(compareByDescending<Map.Entry<EmojiSetAddress, Int>> { it.value }.thenBy { it.key.value })
        .take((budget - published.size).coerceAtLeast(0))
        .map { it.key }
}

/** 公開セット一覧の欄に出すもの。 */
internal enum class EmojiDiscoveryListContent { Loading, Empty, Sets }

/**
 * 一覧を一度取得し終えるまで（タブの切り替え中、取得の開始前を含む）は、空でも読み込み中として扱う。
 * 「登録済みのみ」は端末内の登録内容だけで決まるので待たない。
 */
internal fun emojiDiscoveryListContent(
    listLoaded: Boolean,
    isListLoading: Boolean,
    showRegisteredOnly: Boolean,
    isEmpty: Boolean,
): EmojiDiscoveryListContent = when {
    !isEmpty -> EmojiDiscoveryListContent.Sets
    showRegisteredOnly -> EmojiDiscoveryListContent.Empty
    !listLoaded || isListLoading -> EmojiDiscoveryListContent.Loading
    else -> EmojiDiscoveryListContent.Empty
}

/**
 * 表示・並べ替えに使う並び順。登録人数を数えられない（未ログイン）ときだけ新着順にする。
 * 人数の取得中も選んだ並び順のまま出す（人数0の間はどちらも作成日時順で同じ並びになる）。
 */
internal fun displayedEmojiSort(sort: EmojiSetSort, canCountAdoption: Boolean): EmojiSetSort =
    if (canCountAdoption) sort else EmojiSetSort.Newest

/** 「さらに読み込む」を出すか。自動で取りに行く参照が残っている間や取得中は出さない。 */
internal fun shouldOfferMoreEmojiReferences(
    pendingReferences: Int,
    autoRequests: List<EmojiSetAddress>,
    isLoading: Boolean,
): Boolean = pendingReferences > 0 && autoRequests.isEmpty() && !isLoading
