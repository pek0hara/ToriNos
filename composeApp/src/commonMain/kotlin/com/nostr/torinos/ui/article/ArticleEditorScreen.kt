package com.nostr.torinos.ui.article

import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.DisposableEffect
import com.nostr.torinos.article.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nostr.torinos.account.accountSessionViewModel
import com.nostr.torinos.network.RelayStore
import com.nostr.torinos.network.writableRelayUrls
import com.nostr.torinos.ui.components.AppTopBar
import com.nostr.torinos.ui.components.NetworkImage
import com.nostr.torinos.ui.components.rememberImagePickerLauncher
import com.nostr.torinos.util.logException
import kotlinx.coroutines.CancellationException
import com.nostr.torinos.ui.components.DismissKeyboardOnLeave
import com.nostr.torinos.ui.components.avoidStaleAccessibilityFocus

private fun articleSaveStatusText(state: ArticleEditorState): String = when (state.saveState) {
    ArticleSaveState.Unsaved -> if (state.savedAt != null || state.draft?.revision?.let { it > 0 } == true) "編集中" else "未保存"
    ArticleSaveState.Saving -> "保存中…"
    ArticleSaveState.Saved -> "保存済み" + (state.savedAt?.let { " ${com.nostr.torinos.ui.components.formatTimestamp(it)}" } ?: "")
    ArticleSaveState.Failed -> "保存できませんでした"
}

private enum class ArticleEditorTab(val label: String) {
    Edit("編集"),
    Preview("プレビュー"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleEditorScreen(
    onBack: () -> Unit,
    onPublished: (pubkey: String, identifier: String) -> Unit,
    editPubkey: String? = null,
    editIdentifier: String? = null,
    relayUrl: String? = null,
    viewModelOverride: ArticleEditorViewModel? = null,
    draftId: String? = null,
    onAddPosts: () -> Unit = {},
    onNoteClick: (String) -> Unit = {},
    onUserClick: (String) -> Unit = {},
) {
    // Survive config changes and returning from a pushed route, or a new ViewModel (and a new blank draft) is created.
    val editorInstance = rememberSaveable { com.nostr.torinos.article.articleLocalId() }
    val editorViewModel = viewModelOverride ?: accountSessionViewModel(
        key = "article-editor-$editorInstance-${draftId.orEmpty()}-${editPubkey.orEmpty()}-${editIdentifier.orEmpty()}-${relayUrl.orEmpty()}",
    ) { accountSession ->
        ArticleEditorViewModel(
            editPubkey = editPubkey,
            editIdentifier = editIdentifier,
            relayUrl = relayUrl,
            accountSession = accountSession,
            draftId = draftId,
        )
    }
    val state by editorViewModel.state.collectAsState()
    val relayEntries by RelayStore.entries.collectAsState()
    val writableRelayCount = writableRelayUrls(relayEntries).size
    var selectedTab by rememberSaveable { mutableStateOf(ArticleEditorTab.Edit) }
    var showDiscardConfirmation by rememberSaveable { mutableStateOf(false) }
    var showRevisionPublishConfirmation by remember { mutableStateOf(false) }
    var navigationError by remember { mutableStateOf<String?>(null) }

    fun requestBack() {
        when {
            state.isPublishing -> Unit
            else -> editorViewModel.leave { releaseArticleEditorNavigationGuard(); onBack() }
        }
    }

    val pickCoverImage = rememberImagePickerLauncher { bytes, mimeType ->
        if (bytes != null && mimeType != null) {
            editorViewModel.uploadCoverImage(bytes, mimeType)
        }
    }
    val pickBodyImage = rememberImagePickerLauncher { bytes, mimeType ->
        if (bytes != null && mimeType != null) {
            editorViewModel.uploadBodyImage(bytes, mimeType)
        }
    }

    LaunchedEffect(state.publishedArticle) {
        state.publishedArticle?.let { article ->
            try {
                releaseArticleEditorNavigationGuard()
                onPublished(article.pubkey, article.identifier)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logException("ArticleEditor", e, "Failed to open published article")
                navigationError = e.message ?: "投稿した記事を開けませんでした"
            }
        }
    }
    ArticleEditorBackHandler(enabled = true) {
        requestBack()
    }

    // The app may be killed in the background before the idle save fires.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { editorViewModel.saveNow() }
    DisposableEffect(editorViewModel) { editorViewModel.attachInputGate(); onDispose { editorViewModel.stopPreview(); editorViewModel.detachInputGate() } }
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Column {
                AppTopBar(
                    navigationIcon = {
                        IconButton(
                            onClick = ::requestBack,
                            enabled = !state.isPublishing && !state.frozen,
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "戻る",
                            )
                        }
                    },
                    title = {
                        Column {
                            Text(if (state.isEditing) "記事を編集" else "記事を書く", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(articleSaveStatusText(state), style = MaterialTheme.typography.labelSmall,
                                color = if (state.saveState == ArticleSaveState.Failed) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    },
                    actions = {
                        Button(
                            onClick = {
                                val attempt = state.publishAttempt
                                val draft = state.draft
                                if (attempt != null && (!attempt.complete || attempt.draftCleanupError != null) && draft != null) {
                                    if (draft.content == attempt.draft.content && draft.title == attempt.draft.title && draft.summary == attempt.draft.summary &&
                                        draft.coverImageUrl == attempt.draft.coverImageUrl && draft.topicsInput == attempt.draft.topicsInput && draft.mode == attempt.draft.mode)
                                        editorViewModel.retryPublish() else showRevisionPublishConfirmation = true
                                } else editorViewModel.publish()
                            },
                            enabled = state.canPublish && writableRelayCount > 0,
                            contentPadding = PaddingValues(horizontal = 16.dp),
                        ) {
                            if (state.isPublishing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Text(if (state.isEditing) "更新" else "公開")
                            }
                        }
                        Box {
                            var menuExpanded by remember { mutableStateOf(false) }
                            IconButton(onClick = { menuExpanded = true }) { Icon(Icons.Default.MoreVert, contentDescription = "その他") }
                            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                val menuEnabled = !state.frozen && !state.isPublishing
                                DropdownMenuItem(text = { Text("今すぐ保存") }, enabled = menuEnabled,
                                    onClick = { menuExpanded = false; editorViewModel.saveNow() })
                                if (state.draft?.isMatometr == true && state.draft?.references?.isEmpty() == true) {
                                    DropdownMenuItem(text = { Text("通常記事に変更") }, enabled = menuEnabled,
                                        onClick = { menuExpanded = false; editorViewModel.useRegularArticle() })
                                }
                                DropdownMenuItem(text = { Text("入力を破棄") }, enabled = menuEnabled,
                                    onClick = { menuExpanded = false; showDiscardConfirmation = true })
                            }
                        }
                    },
                )
                PrimaryTabRow(selectedTabIndex = selectedTab.ordinal) {
                    ArticleEditorTab.entries.forEach { tab ->
                        Tab(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            text = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        if (state.isLoadingArticle) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth >= 960.dp) {
                DisposableEffect(state.serializedContent) { editorViewModel.loadPreview(); onDispose { editorViewModel.stopPreview() } }
                Row(Modifier.fillMaxSize().padding(padding)) {
                    ArticleEditContent(state = state, editor = editorViewModel, onAddPosts = { releaseArticleEditorNavigationGuard(); onAddPosts() },
                        onOpenPublished = { editorViewModel.leave { releaseArticleEditorNavigationGuard(); state.publishAttempt?.let { onPublished(it.event.pubkey, it.draft.articleIdentifier) } } },
                        writableRelayCount = writableRelayCount,
                        onTitleChange = editorViewModel::onTitleChange, onSummaryChange = editorViewModel::onSummaryChange,
                        onContentChange = editorViewModel::onContentChange, onCoverImageUrlChange = editorViewModel::onCoverImageUrlChange,
                        onTopicsChange = editorViewModel::onTopicsChange, onPickCoverImage = pickCoverImage,
                        onPickBodyImage = pickBodyImage, onNoteClick = { id -> editorViewModel.leave { releaseArticleEditorNavigationGuard(); onNoteClick(id) } },
                        onUserClick = onUserClick, modifier = Modifier.weight(1f).fillMaxSize().imePadding())
                    ArticlePreviewContent(state, { id -> editorViewModel.leave { releaseArticleEditorNavigationGuard(); onNoteClick(id) } }, onUserClick, editorViewModel::loadPreview, Modifier.weight(1f).fillMaxSize())
                }
            } else {
            // Only the narrow layout follows the tab; the wide layout always shows the preview.
            LaunchedEffect(selectedTab, state.serializedContent) {
                if (selectedTab == ArticleEditorTab.Preview) editorViewModel.loadPreview() else editorViewModel.stopPreview()
            }
            when (selectedTab) {
                ArticleEditorTab.Edit -> ArticleEditContent(
                    state = state,
                    editor = editorViewModel,
                    onAddPosts = { releaseArticleEditorNavigationGuard(); onAddPosts() },
                    onOpenPublished = { editorViewModel.leave { releaseArticleEditorNavigationGuard(); state.publishAttempt?.let { onPublished(it.event.pubkey, it.draft.articleIdentifier) } } },
                    writableRelayCount = writableRelayCount,
                    onTitleChange = editorViewModel::onTitleChange,
                    onSummaryChange = editorViewModel::onSummaryChange,
                    onContentChange = editorViewModel::onContentChange,
                    onCoverImageUrlChange = editorViewModel::onCoverImageUrlChange,
                    onTopicsChange = editorViewModel::onTopicsChange,
                    onPickCoverImage = pickCoverImage,
                    onPickBodyImage = pickBodyImage,
                    onNoteClick = { id -> editorViewModel.leave { releaseArticleEditorNavigationGuard(); onNoteClick(id) } },
                    onUserClick = onUserClick,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding).imePadding(),
                )
                ArticleEditorTab.Preview -> ArticlePreviewContent(
                    state = state,
                    onNoteClick = { id -> editorViewModel.leave { releaseArticleEditorNavigationGuard(); onNoteClick(id) } },
                    onUserClick = onUserClick,
                    onRetry = editorViewModel::loadPreview,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                )
            }
            }
        }
    }

    }

    if (showRevisionPublishConfirmation) AlertDialog(
        onDismissRequest = { showRevisionPublishConfirmation = false }, title = { Text("修正版を公開しますか？") },
        text = { Column {
            Text("旧版は一部のリレーに届いている可能性があります。新しい版の公開準備が保存されたら、旧版への再送を停止します。記事IDと初回公開日時は維持します。")
            val targets = writableRelayUrls(relayEntries)
            Text("今回の公開先: " + targets.joinToString("、"))
            val excluded = state.publishAttempt?.relays?.keys.orEmpty() - targets.toSet()
            if (excluded.isNotEmpty()) Text("旧版が残る可能性がある除外先: " + excluded.joinToString("、"))
        } },
        confirmButton = { TextButton(onClick = { showRevisionPublishConfirmation = false; editorViewModel.publish() }) { Text("修正版を公開") } },
        dismissButton = { TextButton(onClick = { showRevisionPublishConfirmation = false }) { Text("編集を続ける") } },
    )
    if (showDiscardConfirmation) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirmation = false },
            title = { Text("記事の編集を終了しますか？") },
            text = { Text("今回開いた時点の内容に戻します。既存の下書き自体は削除しません。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardConfirmation = false
                        editorViewModel.discard { releaseArticleEditorNavigationGuard(); onBack() }
                    },
                ) {
                    Text("入力を破棄して終了")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirmation = false }) {
                    Text("編集を続ける")
                }
            },
        )
    }

    navigationError?.let { message ->
        AlertDialog(
            onDismissRequest = { navigationError = null },
            title = { Text("記事は投稿されました") },
            text = { Text("記事詳細を開けませんでした。\n$message") },
            confirmButton = {
                TextButton(
                    onClick = {
                        navigationError = null
                        state.publishedArticle?.let { article ->
                            try {
                                onPublished(article.pubkey, article.identifier)
                            } catch (e: Throwable) {
                                logException("ArticleEditor", e, "Retry opening published article failed")
                                navigationError = e.message ?: "投稿した記事を開けませんでした"
                            }
                        }
                    },
                ) {
                    Text("再試行")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        navigationError = null
                        onBack()
                    },
                ) {
                    Text("一覧へ戻る")
                }
            },
        )
    }
}

@Composable
private fun ArticleEditContent(
    state: ArticleEditorState,
    writableRelayCount: Int,
    editor: ArticleEditorViewModel,
    onAddPosts: () -> Unit,
    onOpenPublished: () -> Unit = {},
    onTitleChange: (String) -> Unit,
    onSummaryChange: (String) -> Unit,
    onContentChange: (String) -> Unit,
    onCoverImageUrlChange: (String) -> Unit,
    onTopicsChange: (String) -> Unit,
    onPickCoverImage: () -> Unit,
    onPickBodyImage: () -> Unit,
    onNoteClick: (String) -> Unit,
    onUserClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    DismissKeyboardOnLeave()
    var fullScreen by rememberSaveable { mutableStateOf(false) }
    var settingsExpanded by rememberSaveable { mutableStateOf(false) }
    val enabled = !state.isPublishing && !state.frozen
    // Keep the IME composition; rebuilding the value without it commits Japanese input mid-conversion.
    var composition by remember { mutableStateOf<androidx.compose.ui.text.TextRange?>(null) }
    fun value() = TextFieldValue(state.content, state.selection, composition?.takeIf { it.max <= state.content.length })
    val onValue: (TextFieldValue) -> Unit = { v ->
        editor.onContentChange(v.text); editor.onSelectionChange(v.selection)
        composition = v.composition.takeIf { editor.state.value.content == v.text }
    }
    var expandedCommentaries by rememberSaveable { mutableStateOf(listOf<String>()) }
    var conversionBlocked by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    // The reference that was just moved: outlined briefly and scrolled into view, since a jump can take it off screen.
    var movedReference by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var moveSequence by remember { mutableStateOf(0) }
    val focusScope = rememberCoroutineScope()
    val bodyFocus = remember { FocusRequester() }
    val fullScreenFocus = remember { FocusRequester() }
    val toolbar: @Composable (Boolean) -> Unit = { showFullScreen ->
        // Return the caret to the field the toolbar edited, so typing replaces the selected placeholder.
        val focus = if (showFullScreen) bodyFocus else fullScreenFocus
        fun edit(action: () -> Unit) {
            action()
            // After the click finishes; a request during it is undone when the press releases.
            focusScope.launch { withFrameNanos { }; runCatching { focus.requestFocus() } }
        }
        ArticleMarkdownToolbar(
            enabled = enabled,
            isUploadingImage = state.isUploadingBodyImage,
            onHeading = { edit { editor.toggleLinePrefix("## ") } },
            onBold = { edit { editor.wrapSelection("**", "**", "太字") } },
            onLink = { edit { editor.wrapSelection("[", "](https://)", "リンクテキスト") } },
            onImage = onPickBodyImage,
            onFullScreen = if (showFullScreen) ({ fullScreen = true }) else null,
        )
    }
    if (fullScreen) Dialog(onDismissRequest = { fullScreen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        androidx.compose.material3.Surface(Modifier.fillMaxSize().imePadding()) {
            Column(Modifier.padding(16.dp)) {
                TextButton(onClick = { fullScreen = false }) { Text("編集に戻る") }
                toolbar(false)
                OutlinedTextField(value = value(), onValueChange = onValue, enabled = enabled,
                    shape = MaterialTheme.shapes.extraSmall.copy(topStart = CornerSize(0.dp), topEnd = CornerSize(0.dp)),
                    modifier = Modifier.fillMaxWidth().weight(1f).focusRequester(fullScreenFocus).avoidStaleAccessibilityFocus())
            }
        }
    }
    state.addSheet?.let { sheet ->
        MatometrAddSheet(
            sheet = sheet,
            state = state,
            onInput = editor::onPasteInput,
            onRetry = editor::retryPaste,
            onAdd = editor::commitAdd,
            onPickFromTimeline = { editor.closeAddSheet(); editor.addPosts(onAddPosts) },
            onDismiss = editor::closeAddSheet,
        )
    }
    conversionBlocked?.let { reason ->
        AlertDialog(
            onDismissRequest = { conversionBlocked = null },
            title = { Text("カードに戻せません") },
            text = { Text(reason) },
            confirmButton = { TextButton(onClick = { conversionBlocked = null }) { Text("閉じる") } },
        )
    }
    if (state.draft?.mode == ArticleDraftMode.MatometrStructured) {
        val referenceIds = state.draft.references.map { it.eventId }
        LaunchedEffect(referenceIds) { editor.loadReferenceCards() }
    }
    movedReference?.let { (id, base) ->
        LaunchedEffect(moveSequence) {
            withFrameNanos { }
            // Read the view model directly: the collected `state` can still hold the order from before the move.
            val target = editor.state.value.draft?.references?.indexOfFirst { it.eventId == id }?.takeIf { it >= 0 }
            if (target != null && listState.layoutInfo.visibleItemsInfo.none { it.key == id }) {
                listState.animateScrollToItem(base + target)
            }
            delay(1_500)
            movedReference = null
        }
    }
    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            OutlinedTextField(
                value = state.title,
                onValueChange = onTitleChange,
                modifier = Modifier.fillMaxWidth().avoidStaleAccessibilityFocus(),
                label = { Text("タイトル（必須）") },
                singleLine = true,
                enabled = !state.isPublishing && !state.frozen,
            )
        }
        item {
            Column {
                Text(
                    text = "本文",
                    modifier = Modifier.padding(bottom = 6.dp),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                toolbar(true)
                OutlinedTextField(
                    value = value(),
                    onValueChange = onValue,
                    minLines = 8,
                    maxLines = 24,
                    modifier = Modifier.fillMaxWidth().focusRequester(bodyFocus).avoidStaleAccessibilityFocus(),
                    shape = MaterialTheme.shapes.extraSmall.copy(topStart = CornerSize(0.dp), topEnd = CornerSize(0.dp)),
                    placeholder = {
                        Text("本文を書く")
                    },
                    supportingText = {
                        Text("${state.serializedContent.length} / 10,000")
                    },
                    enabled = !state.isPublishing && !state.frozen,
                )
            }
        }
        state.draft?.takeIf { it.isMatometr }?.let { draft ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (draft.mode == ArticleDraftMode.MatometrStructured) "引用した投稿（${draft.references.size}件）" else "引用した投稿",
                            Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = editor::openAddSheet, enabled = enabled) { Text("投稿を追加") }
                    }
                    MatometrModeSelector(
                        mode = draft.mode,
                        enabled = enabled,
                        onCards = { conversionBlocked = editor.useStructured() },
                        onFreeMarkdown = editor::useFreeMarkdown,
                    )
                }
            }
            if (draft.mode == ArticleDraftMode.MatometrStructured) {
                itemsIndexed(draft.references, key = { _, ref -> ref.eventId }) { index, ref ->
                    MatometrReferenceCard(
                        index = index,
                        count = draft.references.size,
                        reference = ref,
                        state = state,
                        enabled = enabled,
                        commentaryExpanded = ref.eventId in expandedCommentaries,
                        highlighted = movedReference?.first == ref.eventId,
                        onToggleCommentary = {
                            expandedCommentaries = if (ref.eventId in expandedCommentaries) expandedCommentaries - ref.eventId
                                else expandedCommentaries + ref.eventId
                        },
                        onCommentaryChange = { editor.onCommentary(ref.eventId, it) },
                        onMoveTo = { target ->
                            // Lazy-list index of the first card, from this card's current slot.
                            val base = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == ref.eventId }?.index?.minus(index)
                            editor.moveReferenceTo(ref.eventId, target)
                            if (base != null) { movedReference = ref.eventId to base; moveSequence++ }
                        },
                        onRemove = { editor.removeReference(ref.eventId) },
                        onNoteClick = onNoteClick,
                        onUserClick = onUserClick,
                        modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null),
                    )
                }
            }
        }
        item {
            ArticleTagEditor(
                topicsInput = state.topicsInput,
                hasMatometrTag = state.draft?.hasMatometrTag == true,
                enabled = enabled,
                draftKey = state.draft?.draftId,
                onTopicsChange = onTopicsChange,
            )
        }
        item { TextButton(onClick = { settingsExpanded = !settingsExpanded }) { Text(if (settingsExpanded) "記事の設定を閉じる" else "記事の設定を開く") } }
        if (settingsExpanded || state.coverImageUrl.isNotBlank() && !state.coverImageUrl.startsWith("https://", true)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "カバー画像",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                state.coverImageUrl.takeIf { it.isNotBlank() }?.let { imageUrl ->
                    NetworkImage(
                        url = imageUrl,
                        contentDescription = "カバー画像",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop,
                        maxDecodeSizePx = 1000,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = state.coverImageUrl,
                        onValueChange = onCoverImageUrlChange,
                        modifier = Modifier.weight(1f).avoidStaleAccessibilityFocus(),
                        label = { Text("HTTPS URL") },
                        singleLine = true,
                        enabled = !state.isUploadingCover && !state.isPublishing,
                    )
                    IconButton(
                        onClick = onPickCoverImage,
                        enabled = !state.isUploadingCover && !state.isPublishing,
                    ) {
                        if (state.isUploadingCover) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.AddPhotoAlternate, contentDescription = "カバー画像を選択")
                        }
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = state.summary,
                onValueChange = onSummaryChange,
                modifier = Modifier.fillMaxWidth().avoidStaleAccessibilityFocus(),
                label = { Text("概要") },
                minLines = 2,
                maxLines = 4,
                enabled = !state.isPublishing && !state.frozen,
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "投稿先リレー",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (writableRelayCount > 0) {
                        "アプリ設定の投稿先リレー $writableRelayCount 件すべて"
                    } else {
                        "アプリのリレー設定で投稿先を設定してください"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (writableRelayCount == 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
        }
        item {
            ArticleShareAsNoteSection(
                shareAsNote = state.shareAsNote,
                comment = state.shareComment,
                enabled = enabled,
                onShareAsNoteChange = editor::onShareAsNoteChange,
                onCommentChange = editor::onShareCommentChange,
            )
        }
        state.publishDisabledReason?.let { reason -> item { Text(reason, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        if (state.updateConflict) item {
            TextButton(onClick = editor::confirmOverwrite, enabled = enabled) { Text("確認して同じ記事を更新") }
            TextButton(onClick = editor::copyAsNew, enabled = enabled) { Text("自分の編集を別記事にする") }
        }
        state.publishAttempt?.let { attempt ->
            item {
                attempt.relays.forEach { (relay, status) -> Text("$relay: ${status.state} ${status.reason}", style = MaterialTheme.typography.bodySmall) }
                if (!attempt.complete) TextButton(onClick = editor::retryPublish, enabled = enabled && attempt.supersededBy == null) { Text("同じ版を再送") }
                if (attempt.accepted && attempt.draftCleanupError != null) {
                    Text("記事は公開済みです。下書きの削除に失敗しました。", color = MaterialTheme.colorScheme.error)
                    // While relays are left, resending also retries the deletion.
                    if (attempt.complete) TextButton(onClick = editor::retryPublish, enabled = enabled) { Text("下書きの削除を再試行") }
                }
                state.shareError?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (attempt.accepted) TextButton(onClick = editor::retryShare, enabled = enabled) { Text("ポストでの共有を再試行") }
                }
                if (attempt.accepted) TextButton(onClick = onOpenPublished, enabled = enabled) { Text("公開された版を開く") }
            }
        }
        state.error?.let { error ->
            item {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item { Spacer(modifier = Modifier.height(32.dp)) }
    }
}

/** 公開と同時に kind:1 のポストで記事を共有する設定。ひとことが空なら記事のタイトルを添える。 */
@Composable
private fun ArticleShareAsNoteSection(
    shareAsNote: Boolean,
    comment: String,
    enabled: Boolean,
    onShareAsNoteChange: (Boolean) -> Unit,
    onCommentChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = shareAsNote, enabled = enabled, role = Role.Checkbox, onValueChange = onShareAsNoteChange),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = shareAsNote, onCheckedChange = null, enabled = enabled)
            Column(modifier = Modifier.padding(start = 8.dp)) {
                Text("公開と同時にポストでも共有する", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "記事へのリンクを付けた kind:1 のポストを投稿します",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (shareAsNote) {
            OutlinedTextField(
                value = comment,
                onValueChange = onCommentChange,
                modifier = Modifier.fillMaxWidth().avoidStaleAccessibilityFocus(),
                label = { Text("ひとこと（任意）") },
                placeholder = { Text("空欄なら記事のタイトルを添えます") },
                minLines = 2,
                maxLines = 4,
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun ArticlePreviewContent(
    state: ArticleEditorState,
    onNoteClick: (String) -> Unit,
    onUserClick: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.title.isBlank() && state.content.isBlank()) {
        Box(modifier = modifier.padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                text = "タイトルと本文を入力するとプレビューを表示します",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text(
                text = state.title.ifBlank { "無題の記事" },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        state.coverImageUrl.takeIf { it.isNotBlank() }?.let { imageUrl ->
            item {
                NetworkImage(
                    url = imageUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                    maxDecodeSizePx = 1200,
                )
            }
        }
        item {
            MarkdownBody(
                content = state.serializedContent,
                articleQuoteIds = emptyList(),
                quotedEvents = state.quotedEvents,
                quotedProfiles = state.quotedProfiles,
                loadingQuoteIds = state.loadingQuoteIds,
                onUserClick = onUserClick,
                onNoteClick = onNoteClick,
            )
        }
        item { TextButton(onClick = onRetry) { Text("引用を再取得") } }
        articlePublicationTopics(state.topicsInput, state.draft?.hasMatometrTag == true).takeIf { it.isNotEmpty() }?.let { topics ->
            item {
                Text(
                    text = topics.joinToString("  ") { "#$it" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        state.error?.let { error ->
            item {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
