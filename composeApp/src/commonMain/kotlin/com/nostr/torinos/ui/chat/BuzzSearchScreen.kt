package com.nostr.torinos.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nostr.torinos.account.LocalAccountSession
import com.nostr.torinos.account.accountSessionViewModel
import com.nostr.torinos.chat.BuzzMessageHit
import com.nostr.torinos.chat.BuzzSearchChannel
import com.nostr.torinos.chat.BuzzSearchPerson
import com.nostr.torinos.chat.BuzzSearchTarget
import com.nostr.torinos.chat.GroupLayout
import com.nostr.torinos.chat.GroupRef
import com.nostr.torinos.network.ProfileRepository
import com.nostr.torinos.ui.components.avoidStaleAccessibilityFocus
import com.nostr.torinos.ui.components.rememberDismissKeyboard

/**
 * Buzz コミュニティ内の検索（docs/buzz-search-design.md）。公式モバイル版の検索画面と同じ構成:
 * 検索欄、「すべて / メッセージ / チャンネル / 人」、結果（チャンネル・人・メッセージの順）、入力が空なら最近の検索。
 */
@Composable
fun BuzzSearchScreen(
    relayUrl: String,
    onBack: () -> Unit,
    onOpenGroup: (GroupRef, GroupLayout) -> Unit,
    onOpenThread: (GroupRef, String) -> Unit,
    onUserClick: (String) -> Unit,
) {
    val session = LocalAccountSession.current
    val viewModel = accountSessionViewModel(key = "buzz-search-${session?.sessionId}-$relayUrl") { s ->
        BuzzSearchViewModel(s?.chat, relayUrl)
    }
    val state by viewModel.state.collectAsState()
    var input by rememberSaveable { mutableStateOf(state.query) }
    val focusRequester = remember { FocusRequester() }
    val dismissKeyboard = rememberDismissKeyboard()
    // 開いたらすぐ入力できるようにする（結果から戻ったときは出さない）。
    var focusedOnce by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!focusedOnce) {
            focusedOnce = true
            runCatching { focusRequester.requestFocus() }
        }
    }
    fun leave(action: () -> Unit) {
        viewModel.recordQuery()
        dismissKeyboard()
        action()
    }
    val openTarget: (BuzzMessageHit) -> Unit = { hit ->
        leave {
            when (val target = hit.target) {
                is BuzzSearchTarget.Thread -> onOpenThread(target.ref, target.rootId)
                is BuzzSearchTarget.Channel -> onOpenGroup(target.ref, state.layoutOf(target.ref))
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.background).padding(bottom = 6.dp)) {
                Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { dismissKeyboard(); onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                    SearchField(
                        value = input,
                        onValueChange = { input = it; viewModel.onQueryChange(it) },
                        onClear = { input = ""; viewModel.onQueryChange(""); runCatching { focusRequester.requestFocus() } },
                        onSearch = { viewModel.recordQuery(input); dismissKeyboard() },
                        focusRequester = focusRequester,
                        modifier = Modifier.weight(1f),
                    )
                }
                // 狭い画面でも切れないよう、横にスクロールできるようにする。
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BuzzSearchFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = state.filter == filter,
                            onClick = { viewModel.setFilter(filter) },
                            label = { Text(filter.label, maxLines = 1) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.query.isEmpty() && state.recent.isNotEmpty() -> RecentSearches(
                    searches = state.recent,
                    onPick = { query ->
                        input = query
                        viewModel.onQueryChange(query)
                        viewModel.recordQuery(query)
                    },
                    onClear = viewModel::clearRecent,
                )
                state.query.isEmpty() -> SearchHint(modifier = Modifier.align(Alignment.Center))
                !state.isLoading && state.hasNoResults -> Text(
                    "「${state.query}」に一致するものはありません",
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                else -> SearchResults(
                    state = state,
                    onOpenChannel = { channel -> leave { onOpenGroup(channel.ref, channel.metadata.layout) } },
                    onOpenPerson = { person -> leave { onUserClick(person.pubkey) } },
                    onOpenMessage = openTarget,
                    onRetryMessages = viewModel::retryMessages,
                    onLoadMore = viewModel::loadMore,
                )
            }
        }
    }
}

/** 丸い検索欄（公式の検索欄と同じ形）。TextField の最小の高さで文字が切れないよう、BasicTextField で作る。 */
@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit,
    onSearch: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
    Row(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f).padding(vertical = 10.dp).focusRequester(focusRequester).avoidStaleAccessibilityFocus(),
            singleLine = true,
            textStyle = textStyle,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text("検索", style = textStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    inner()
                }
            },
        )
        if (value.isNotEmpty()) {
            IconButton(onClick = onClear, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Close, contentDescription = "入力を消す", modifier = Modifier.size(18.dp))
            }
        } else {
            Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
private fun SearchHint(modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            Icons.Default.Search,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "メッセージ・チャンネル・人を検索",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        // 手元の控えは部分一致、リレーは語の単位（docs/buzz-search-design.md 2 章）。
        Text(
            "最近の発言は文の途中の一部でも見つかります。古い発言は語の単位で探します",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun RecentSearches(searches: List<String>, onPick: (String) -> Unit, onClear: () -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "recent-header") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "最近の検索",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onClear) { Text("消去") }
            }
        }
        items(searches, key = { "recent-$it" }) { query ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onPick(query) }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Text(query, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SearchResults(
    state: BuzzSearchUiState,
    onOpenChannel: (BuzzSearchChannel) -> Unit,
    onOpenPerson: (BuzzSearchPerson) -> Unit,
    onOpenMessage: (BuzzMessageHit) -> Unit,
    onRetryMessages: () -> Unit,
    onLoadMore: () -> Unit,
) {
    val authors = remember(state.shownMessages) { state.shownMessages.map { it.author }.toSet() }
    val profiles by remember(authors) { ProfileRepository.observe(authors) }.collectAsState(emptyMap())
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        if (state.shownChannels.isNotEmpty()) {
            item(key = "h-channels") { SectionLabel("チャンネル") }
            items(state.shownChannels, key = { "c-" + it.ref.groupId }) { channel ->
                ChannelRow(channel, onClick = { onOpenChannel(channel) })
            }
        }
        if (state.shownPeople.isNotEmpty()) {
            item(key = "h-people") { SectionLabel("人") }
            items(state.shownPeople, key = { "p-" + it.pubkey }) { person ->
                PersonRow(person, onClick = { onOpenPerson(person) })
            }
        }
        if (state.shownMessages.isNotEmpty()) {
            item(key = "h-messages") { SectionLabel("メッセージ") }
            items(state.shownMessages, key = { "m-" + it.id }) { hit ->
                MessageRow(
                    hit = hit,
                    authorName = profiles[hit.author]?.bestName,
                    authorPicture = profiles[hit.author]?.picture,
                    channel = state.channelInfo[hit.ref.groupId],
                    onClick = { onOpenMessage(hit) },
                )
            }
        }
        if (state.showsMessageFailure) {
            item(key = "messages-failed") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "メッセージを検索できませんでした",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onRetryMessages) { Text("再試行") }
                }
            }
        }
        // 押したときだけ、リレーの続きと手元の控えを 500 件ずつさかのぼる（docs/buzz-search-design.md 2.2）。
        if (state.showsLoadMore && !state.messagesLoading) {
            item(key = "load-more") {
                Box(modifier = Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                    if (state.loadingMore) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    } else {
                        TextButton(onClick = onLoadMore) { Text("さらに古い発言も探す") }
                    }
                }
            }
        }
        if (state.isLoading) {
            item(key = "loading") {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ChannelRow(channel: BuzzSearchChannel, onClick: () -> Unit) {
    val meta = channel.metadata
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GroupIcon(picture = meta.picture, locked = meta.isPrivate, size = 36, forum = meta.layout == GroupLayout.Forum)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(meta.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val note = when {
                meta.isArchived -> "アーカイブ済み"
                channel.joined -> "参加中"
                else -> "未参加"
            }
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PersonRow(person: BuzzSearchPerson, onClick: () -> Unit) {
    val name = person.profile.bestName
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChatAvatar(pubkey = person.pubkey, name = name, pictureUrl = person.profile.picture, size = 36)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                name ?: (person.pubkey.take(8) + "…"),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            person.profile.name?.takeIf { it.isNotBlank() && it != name }?.let { handle ->
                Text(
                    "@$handle",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MessageRow(
    hit: BuzzMessageHit,
    authorName: String?,
    authorPicture: String?,
    channel: com.nostr.torinos.chat.GroupMetadata?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        ChatAvatar(pubkey = hit.author, name = authorName, pictureUrl = authorPicture, size = 36)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    authorName ?: (hit.author.take(8) + "…"),
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    chatRelativeTime(hit.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            // 公式の「Message in #チャンネル」。DM は「DM」、名前が分からなければ出さない。
            val place = when {
                channel?.layout == GroupLayout.Direct -> "DM"
                channel != null && channel.name.isNotBlank() -> "#" + channel.name.removePrefix("#")
                else -> null
            }
            if (place != null) {
                Text(
                    place,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                hit.preview.ifEmpty { "（本文なし）" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    HorizontalDivider(modifier = Modifier.padding(start = 64.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}
