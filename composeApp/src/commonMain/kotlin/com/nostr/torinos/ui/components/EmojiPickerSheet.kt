package com.nostr.torinos.ui.components

import com.nostr.torinos.emoji.PublishedEmojiSet
import com.nostr.torinos.emoji.EmojiSetAddress
import com.nostr.torinos.emoji.EmojiPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.nostr.torinos.network.RelayStore
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nostr.torinos.model.ReactionOption
import com.nostr.torinos.emoji.CustomEmoji
import com.nostr.torinos.emoji.EmojiSetDiscovery
import com.nostr.torinos.emoji.EmojiSetDiscoveryState
import com.nostr.torinos.emoji.normalizeEmojiSearchQuery

internal data class EmojiPickerSection(
    val title: String,
    val options: List<ReactionOption>,
)

/** ピッカー下部のボタンで選ぶ絞り込み。同時に1つだけ有効。 */
internal sealed interface EmojiPickerFilter {
    data object All : EmojiPickerFilter
    data object Favorites : EmojiPickerFilter
    data object Custom : EmojiPickerFilter
    data class Category(val category: StandardEmojiCategory) : EmojiPickerFilter
}

/**
 * 表示するセクション。検索語（正規化済み）があれば絞り込みより優先して検索結果だけを出す。
 * 「すべて」はお気に入り → 最近使った項目 → カスタム絵文字 → 標準カテゴリの順で、空のセクションは出さない。
 */
internal fun emojiPickerSections(
    query: String,
    filter: EmojiPickerFilter,
    favorites: List<ReactionOption>,
    recent: List<ReactionOption>,
    custom: List<ReactionOption>,
    searchableCustom: List<ReactionOption.Custom>,
): List<EmojiPickerSection> {
    if (query.isNotBlank()) {
        val unicodeMatches = STANDARD_EMOJI_CATEGORIES.flatMap { category ->
            category.emojis.filter { emoji ->
                query in emoji ||
                    query in category.label.lowercase() ||
                    EMOJI_SEARCH_KEYWORDS[emoji].orEmpty().any { query in it }
            }
        }.distinct().map { ReactionOption.Unicode(it) }
        val customMatches = searchableCustom.filter { query in it.shortcode.lowercase() }
        return listOf(EmojiPickerSection("検索結果", customMatches + unicodeMatches))
    }
    return when (filter) {
        EmojiPickerFilter.Custom -> listOf(EmojiPickerSection("カスタム絵文字", custom))
        EmojiPickerFilter.Favorites -> listOf(EmojiPickerSection("お気に入り", favorites))
        is EmojiPickerFilter.Category -> listOf(filter.category.toSection())
        EmojiPickerFilter.All -> listOf(
            EmojiPickerSection("お気に入り", favorites),
            EmojiPickerSection("最近使った項目", recent),
            EmojiPickerSection("カスタム絵文字", custom),
        ).filter { it.options.isNotEmpty() } + STANDARD_EMOJI_CATEGORIES.map { it.toSection() }
    }
}

private fun StandardEmojiCategory.toSection() = EmojiPickerSection(label, emojis.map { ReactionOption.Unicode(it) })

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StandardEmojiPickerSheet(
    onDismiss: () -> Unit,
    onSelect: (ReactionOption) -> Unit,
    onOpenCustomEmojiSettings: (() -> Unit)? = null,
) {
    DismissKeyboardOnLeave()
    val emojiPreferences = rememberEmojiPreferences()
    val savedCustomEmojis = remember(emojiPreferences.available) {
        emojiPreferences.available.sortedBy { it.shortcode.lowercase() }
    }
    val recentReactions = emojiPreferences.recent
    val favoriteEmojis = emojiPreferences.favorites
    val discoverySnapshot by EmojiSetDiscovery.state.collectAsState()
    val selectedRelay by RelayStore.selectedEmojiRelayUrl.collectAsState()
    val relaysLoaded by RelayStore.isLoaded.collectAsState()
    val discoveryState = discoverySnapshot.takeIf { it.relayUrl == selectedRelay }
        ?: EmojiSetDiscoveryState(relayUrl = selectedRelay)
    DisposableEffect(selectedRelay, relaysLoaded) {
        val release = if (relaysLoaded) selectedRelay?.let(EmojiSetDiscovery::acquire) else null
        onDispose { release?.invoke() }
    }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf<EmojiPickerFilter>(EmojiPickerFilter.All) }
    fun select(next: EmojiPickerFilter) {
        query = ""
        filter = next
    }
    val normalizedQuery = normalizeEmojiSearchQuery(query)
    val customOptions = remember(emojiPreferences, savedCustomEmojis) {
        savedCustomEmojis.map { emojiPreferences.toReactionOption(it) }
    }
    val searchableCustomOptions = remember(emojiPreferences, savedCustomEmojis, discoveryState.publishedSets) {
        customEmojiSearchOptions(
            registered = savedCustomEmojis,
            published = discoveryState.publishedSets,
            setAddressOf = emojiPreferences::setAddressOf,
        )
    }
    val recentOptions = remember(emojiPreferences, recentReactions) {
        recentReactions
            .map { it.toReactionOption(emojiPreferences::setAddressOf) }
            .distinctBy { it.key }
            .take(24)
    }
    val favoriteOptions = remember(emojiPreferences, favoriteEmojis) {
        favoriteEmojis.map { emojiPreferences.toReactionOption(it) }
    }
    val visibleSections = remember(
        normalizedQuery, filter, customOptions, favoriteOptions, recentOptions, searchableCustomOptions,
    ) {
        emojiPickerSections(
            query = normalizedQuery,
            filter = filter,
            favorites = favoriteOptions,
            recent = recentOptions,
            custom = customOptions,
            searchableCustom = searchableCustomOptions,
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.78f)
                .padding(horizontal = 16.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("検索") },
                leadingIcon = {
                    Icon(imageVector = Icons.Default.Search, contentDescription = null)
                },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "検索文字を消去",
                            )
                        }
                    }
                } else {
                    null
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                shape = RoundedCornerShape(14.dp),
            )
            Spacer(modifier = Modifier.height(10.dp))

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 42.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                visibleSections.forEach { section ->
                    item(
                        key = "title-${section.title}",
                        span = { GridItemSpan(maxLineSpan) },
                    ) {
                        EmojiPickerSectionTitle(section.title)
                    }
                    items(
                        items = section.options,
                        key = { "${section.title}-${it.key}" },
                    ) { option ->
                        EmojiPickerGridTile(option = option, onSelect = onSelect)
                    }
                }

                if (visibleSections.all { it.options.isEmpty() }) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        if (normalizedQuery.isNotBlank() && discoveryState.isLoading) {
                            Row(
                                modifier = Modifier.padding(vertical = 24.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                                Text(
                                    text = "公開絵文字を検索中…",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        } else {
                            Text(
                                text = "一致する絵文字はありません",
                                modifier = Modifier.padding(vertical = 24.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EmojiCategoryButton(
                    icon = "",
                    iconVector = Icons.Default.History,
                    label = "すべてとよく使う項目",
                    selected = filter == EmojiPickerFilter.All,
                    onClick = { select(EmojiPickerFilter.All) },
                )
                EmojiCategoryButton(
                    icon = "",
                    iconVector = Icons.Default.Star,
                    label = "お気に入り",
                    selected = filter == EmojiPickerFilter.Favorites,
                    onClick = { select(EmojiPickerFilter.Favorites) },
                )
                EmojiCategoryButton(
                    icon = "✦",
                    customImageUrl = savedCustomEmojis.firstOrNull()?.imageUrl,
                    label = "カスタム絵文字",
                    selected = filter == EmojiPickerFilter.Custom,
                    onClick = { select(EmojiPickerFilter.Custom) },
                )
                STANDARD_EMOJI_CATEGORIES.forEach { category ->
                    EmojiCategoryButton(
                        icon = category.icon,
                        label = category.label,
                        selected = filter == EmojiPickerFilter.Category(category),
                        onClick = { select(EmojiPickerFilter.Category(category)) },
                    )
                }
            }

            onOpenCustomEmojiSettings?.let { onOpenSettings ->
                TextButton(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(bottom = 4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text("絵文字を追加")
                }
            } ?: Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/** 検索候補。登録済み → 公開セットの順で、同じ絵文字は1つにする。セットのアドレスも付ける。 */
internal fun customEmojiSearchOptions(
    registered: List<CustomEmoji>,
    published: List<PublishedEmojiSet>,
    setAddressOf: (CustomEmoji) -> EmojiSetAddress? = { null },
): List<ReactionOption.Custom> =
    (
        registered.map { ReactionOption.Custom(it.shortcode, it.imageUrl, setAddressOf(it)) } +
            published.flatMap { set -> set.emojis.map { ReactionOption.Custom(it.shortcode, it.imageUrl, set.address) } }
        )
        .distinctBy { it.key }

private fun EmojiPreferences.toReactionOption(emoji: CustomEmoji) =
    ReactionOption.Custom(emoji.shortcode, emoji.imageUrl, setAddressOf(emoji))

@Composable
private fun EmojiPickerSectionTitle(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun EmojiPickerGridTile(
    option: ReactionOption,
    onSelect: (ReactionOption) -> Unit,
) {
    val density = LocalDensity.current
    val decodeSizePx = remember(density) { with(density) { 30.dp.roundToPx() } }
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable { onSelect(option) }
            .semantics {
                contentDescription = when (option) {
                    is ReactionOption.Unicode -> option.value
                    is ReactionOption.Custom -> ":${option.shortcode}:"
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when (option) {
            is ReactionOption.Unicode -> Text(text = option.value, fontSize = 27.sp)
            is ReactionOption.Custom -> NetworkImage(
                url = option.imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                maxDecodeSizePx = decodeSizePx,
                modifier = Modifier.size(30.dp),
            )
        }
    }
}

@Composable
private fun EmojiCategoryButton(
    icon: String,
    iconVector: ImageVector? = null,
    customImageUrl: String? = null,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val density = LocalDensity.current
    val decodeSizePx = remember(density) { with(density) { 24.dp.roundToPx() } }
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (iconVector != null) {
            Icon(
                imageVector = iconVector,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (customImageUrl != null) {
            NetworkImage(
                url = customImageUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                maxDecodeSizePx = decodeSizePx,
                modifier = Modifier.size(24.dp),
            )
        } else {
            Text(
                text = icon,
                fontSize = 21.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
