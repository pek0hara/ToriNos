package com.nostr.torinos.ui.components

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nostr.torinos.model.ReactionOption
import com.nostr.torinos.network.CustomEmojiStore
import com.nostr.torinos.network.RecentReaction

private data class EmojiPickerSection(
    val title: String,
    val options: List<ReactionOption>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StandardEmojiPickerSheet(
    onDismiss: () -> Unit,
    onSelect: (ReactionOption) -> Unit,
    onOpenCustomEmojiSettings: (() -> Unit)? = null,
) {
    val savedCustomEmojis by CustomEmojiStore.emojis.collectAsState()
    val recentReactions by CustomEmojiStore.recentReactions.collectAsState()
    var query by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<StandardEmojiCategory?>(null) }
    var customOnly by remember { mutableStateOf(false) }
    val normalizedQuery = query.trim().trim(':').lowercase()
    val customOptions = remember(savedCustomEmojis) {
        savedCustomEmojis.map { ReactionOption.Custom(it.shortcode, it.imageUrl) }
    }
    val recentOptions = remember(recentReactions, savedCustomEmojis) {
        val customEmojiMap = savedCustomEmojis.associateBy { it.shortcode }
        recentReactions
            .asSequence()
            .mapNotNull { recent ->
                when (recent.kind) {
                    RecentReaction.UnicodeKind -> ReactionOption.Unicode(recent.value)
                    RecentReaction.CustomKind -> customEmojiMap[recent.value]?.let {
                        ReactionOption.Custom(it.shortcode, it.imageUrl)
                    }
                    else -> null
                }
            }
            .distinctBy { it.key }
            .take(16)
            .toList()
    }
    val visibleSections = remember(normalizedQuery, selectedCategory, customOnly, customOptions) {
        when {
            normalizedQuery.isNotBlank() -> {
                val unicodeMatches = STANDARD_EMOJI_CATEGORIES.flatMap { category ->
                    category.emojis.filter { emoji ->
                        normalizedQuery in emoji ||
                            normalizedQuery in category.label.lowercase() ||
                            EMOJI_SEARCH_KEYWORDS[emoji].orEmpty().any { normalizedQuery in it }
                    }
                }.distinct().map { ReactionOption.Unicode(it) }
                val customMatches = customOptions.filter {
                    normalizedQuery in it.shortcode.lowercase()
                }
                listOf(EmojiPickerSection("検索結果", customMatches + unicodeMatches))
            }
            customOnly -> listOf(EmojiPickerSection("カスタム絵文字", customOptions))
            selectedCategory != null -> listOf(
                EmojiPickerSection(
                    selectedCategory!!.label,
                    selectedCategory!!.emojis.map { ReactionOption.Unicode(it) },
                ),
            )
            else -> if (customOptions.isNotEmpty()) {
                listOf(EmojiPickerSection("カスタム絵文字", customOptions))
            } else {
                emptyList()
            } + STANDARD_EMOJI_CATEGORIES.map { category ->
                EmojiPickerSection(
                    category.label,
                    category.emojis.map { ReactionOption.Unicode(it) },
                )
            }
        }
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
                if (
                    normalizedQuery.isBlank() &&
                    selectedCategory == null &&
                    !customOnly &&
                    recentOptions.isNotEmpty()
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        EmojiPickerSectionTitle("よく使う項目")
                    }
                    items(recentOptions, key = { "recent-${it.key}" }) { option ->
                        EmojiPickerGridTile(option = option, onSelect = onSelect)
                    }
                }

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
                        Text(
                            text = "一致する絵文字はありません",
                            modifier = Modifier.padding(vertical = 24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
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
                    selected = selectedCategory == null && !customOnly,
                    onClick = {
                        query = ""
                        selectedCategory = null
                        customOnly = false
                    },
                )
                EmojiCategoryButton(
                    icon = "✦",
                    customImageUrl = savedCustomEmojis.firstOrNull()?.imageUrl,
                    label = "カスタム絵文字",
                    selected = customOnly,
                    onClick = {
                        query = ""
                        selectedCategory = null
                        customOnly = true
                    },
                )
                STANDARD_EMOJI_CATEGORIES.forEach { category ->
                    EmojiCategoryButton(
                        icon = category.icon,
                        label = category.label,
                        selected = selectedCategory == category,
                        onClick = {
                            query = ""
                            selectedCategory = category
                            customOnly = false
                        },
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
