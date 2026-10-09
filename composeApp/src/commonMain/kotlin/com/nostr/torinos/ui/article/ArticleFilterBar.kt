package com.nostr.torinos.ui.article

import com.nostr.torinos.ui.theme.appSp
import com.nostr.torinos.model.MATOMETR_TOPIC
import com.nostr.torinos.model.ArticleItem
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.filled.ArrowDropDown

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 記事一覧上部のセグメントで選ぶ著者フィルター。 */
enum class ArticleAuthorFilter(val label: String) {
    All("すべて"),
    Following("フォロー"),
    Own("自分"),
}

/** 未ログイン時は著者フィルターを使えないため、常に全著者を対象にする。 */
internal fun ArticleAuthorFilter.toAuthorScope(ownPubkey: String?): ArticleAuthorScope =
    when {
        ownPubkey == null -> ArticleAuthorScope.All
        this == ArticleAuthorFilter.All -> ArticleAuthorScope.All
        this == ArticleAuthorFilter.Following -> ArticleAuthorScope.Following
        else -> ArticleAuthorScope.Only(ownPubkey)
    }

/**
 * 一覧の上に重ねて表示するフィルター。著者セグメント([authorFilter]がnullなら出さない)と、
 * [tagCandidates]を渡した時はその右にタグの選択欄を置く。[tagCandidates]がnullなら、
 * 絞り込み中の[topic]だけを2行目に解除できるチップで出す。
 */
@Composable
internal fun ArticleFloatingFilters(
    authorFilter: ArticleAuthorFilter?,
    onAuthorFilterChange: (ArticleAuthorFilter) -> Unit,
    topic: String?,
    onClearTopic: () -> Unit,
    tagCandidates: List<String>? = null,
    onSelectTag: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(top = FloatingFilterTopPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(FloatingFilterSpacing),
    ) {
        if (authorFilter != null || tagCandidates != null) Row(
            modifier = Modifier.height(if (authorFilter != null) SegmentHeight else TopicChipHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (authorFilter != null) {
                ArticleAuthorSegmentedControl(
                    selected = authorFilter,
                    onSelected = onAuthorFilterChange,
                )
            }
            if (tagCandidates != null) {
                ArticleTagSelector(topic = topic, candidates = tagCandidates, onSelect = onSelectTag, onClear = onClearTopic)
            }
        }
        if (tagCandidates == null && topic != null) {
            ArticleTopicChip(topic = topic, onClear = onClearTopic)
        }
    }
}

/** [ArticleFloatingFilters]の下に一覧の先頭が隠れないよう空ける高さ。 */
internal fun articleFloatingFiltersHeight(hasAuthorFilter: Boolean, hasTopic: Boolean, hasTagSelector: Boolean = false): Dp {
    val firstRow = when {
        hasAuthorFilter -> SegmentHeight
        hasTagSelector -> TopicChipHeight
        else -> null
    }
    // With a tag selector the chosen topic is shown in it, so there is no second row.
    val topicRow = hasTopic && !hasTagSelector
    if (firstRow == null && !topicRow) return 0.dp
    var height = FloatingFilterTopPadding + (firstRow ?: 0.dp)
    if (firstRow != null && topicRow) height += FloatingFilterSpacing
    if (topicRow) height += TopicChipHeight
    return height - 2.dp
}

/**
 * タグの選択欄。未選択なら「タグ ▽」、選択中はタグ名と解除の✕を出す。本体のタップで候補を開き直せる。
 * `#matometr`はNostr上ではほかのタグと同じなので、専用ボタンにせず候補の先頭に置く。
 */
@Composable
private fun ArticleTagSelector(
    topic: String?,
    candidates: List<String>,
    onSelect: (String) -> Unit,
    onClear: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = topic != null
    val content = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box {
        Surface(
            modifier = Modifier.height(TopicChipHeight).widthIn(max = 150.dp),
            shape = RoundedCornerShape(16.dp),
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            shadowElevation = 4.dp,
            onClick = { expanded = true },
        ) {
            Row(
                modifier = Modifier.padding(start = 12.dp, end = if (selected) 4.dp else 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = topic?.let { "#$it" } ?: "タグ",
                    color = content,
                    fontSize = 13.appSp,
                    lineHeight = 18.appSp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (selected) {
                    Box(
                        modifier = Modifier.size(28.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClear),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "タグの絞り込みを解除", tint = content, modifier = Modifier.size(16.dp))
                    }
                } else {
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
                }
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            candidates.forEach { tag ->
                DropdownMenuItem(
                    text = { Text("#$tag", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingIcon = if (tag.equals(topic, ignoreCase = true)) {
                        { Icon(Icons.Default.Check, contentDescription = "選択中", modifier = Modifier.size(18.dp)) }
                    } else null,
                    onClick = { expanded = false; onSelect(tag) },
                )
            }
        }
    }
}

/**
 * タグの候補。`#matometr`を常に先頭にし、最近選んだタグ、読み込み済みの記事に多いタグの順に並べる。
 * 大文字小文字だけ違うタグは同じものとして扱う。
 */
internal fun articleTagCandidates(articles: List<ArticleItem>, recent: List<String>, frequentLimit: Int = 5): List<String> {
    val frequent = articles.flatMap { article -> article.meta.topics.distinctBy { it.lowercase() } }
        .groupingBy { it.lowercase() }.eachCount()
        .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .map { it.key }
        .filterNot { it == MATOMETR_TOPIC }
        .take(frequentLimit)
    return (listOf(MATOMETR_TOPIC) + recent + frequent).filter { it.isNotBlank() }.distinctBy { it.lowercase() }
}

@Composable
private fun ArticleAuthorSegmentedControl(
    selected: ArticleAuthorFilter,
    onSelected: (ArticleAuthorFilter) -> Unit,
) {
    Surface(
        // 文字を大きくしても選択肢が切れないよう、240dpを最小にして一番長い選択肢に合わせて広げる。
        modifier = Modifier
            .widthIn(min = 240.dp)
            .width(IntrinsicSize.Max)
            .height(SegmentHeight),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 6.dp,
    ) {
        Row(modifier = Modifier.padding(3.dp)) {
            ArticleAuthorFilter.entries.forEach { filter ->
                val isSelected = selected == filter
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clip(RoundedCornerShape(9.dp))
                        .background(
                            if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        )
                        .clickable { onSelected(filter) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = filter.label,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontSize = 13.appSp,
                        lineHeight = 18.appSp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun ArticleTopicChip(
    topic: String,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.height(TopicChipHeight),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        shadowElevation = 4.dp,
        onClick = onClear,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "#$topic",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
            Icon(
                Icons.Default.Close,
                contentDescription = "トピックの絞り込みを解除",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * 記事のトピック一覧。[onTopicClick]がnullなら表示だけを行い、タップに反応しない。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ArticleTopics(
    topics: List<String>,
    onTopicClick: ((String) -> Unit)?,
    maxLines: Int = Int.MAX_VALUE,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.labelMedium,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        maxLines = maxLines,
    ) {
        topics.forEach { topic ->
            Text(
                text = "#$topic",
                style = style,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                modifier = if (onTopicClick != null) {
                    Modifier.clickable { onTopicClick(topic) }
                } else {
                    Modifier
                },
            )
        }
    }
}

private val FloatingFilterTopPadding = 10.dp
private val FloatingFilterSpacing = 6.dp
private val SegmentHeight = 40.dp
private val TopicChipHeight = 32.dp
