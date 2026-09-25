package com.nostr.torinos.ui.article

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.unit.sp

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
 * 一覧の上に重ねて表示するフィルター。[authorFilter]がnullなら著者セグメントを出さず、
 * [topic]がnullならトピックのチップを出さない。
 */
@Composable
internal fun ArticleFloatingFilters(
    authorFilter: ArticleAuthorFilter?,
    onAuthorFilterChange: (ArticleAuthorFilter) -> Unit,
    topic: String?,
    onClearTopic: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(top = FloatingFilterTopPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(FloatingFilterSpacing),
    ) {
        if (authorFilter != null) {
            ArticleAuthorSegmentedControl(
                selected = authorFilter,
                onSelected = onAuthorFilterChange,
            )
        }
        if (topic != null) {
            ArticleTopicChip(topic = topic, onClear = onClearTopic)
        }
    }
}

/** [ArticleFloatingFilters]の下に一覧の先頭が隠れないよう空ける高さ。 */
internal fun articleFloatingFiltersHeight(hasAuthorFilter: Boolean, hasTopic: Boolean): Dp {
    if (!hasAuthorFilter && !hasTopic) return 0.dp
    var height = FloatingFilterTopPadding
    if (hasAuthorFilter) height += SegmentHeight
    if (hasAuthorFilter && hasTopic) height += FloatingFilterSpacing
    if (hasTopic) height += TopicChipHeight
    return height - 2.dp
}

@Composable
private fun ArticleAuthorSegmentedControl(
    selected: ArticleAuthorFilter,
    onSelected: (ArticleAuthorFilter) -> Unit,
) {
    Surface(
        modifier = Modifier
            .width(240.dp)
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
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
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
