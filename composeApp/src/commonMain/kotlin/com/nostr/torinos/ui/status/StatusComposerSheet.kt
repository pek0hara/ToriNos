package com.nostr.torinos.ui.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties
import com.nostr.torinos.ui.components.formatTimestamp
import com.nostr.torinos.ui.components.rememberDismissKeyboard
import com.nostr.torinos.status.GENERAL_STATUS_IDENTIFIER
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import com.nostr.torinos.ui.components.DismissKeyboardOnLeave

private data class StatusComposerFields(
    val content: String,
    val expirationOption: StatusExpirationOption,
    val customExpiration: Long,
    val referenceUrl: String,
    val showReferenceUrl: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StatusComposerSheet(
    title: String,
    actionLabel: String? = null,
    isPublishing: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSubmit: (statusTag: String, content: String, expiration: Long?, referenceUrl: String?) -> Unit,
    initialStatusTag: String = GENERAL_STATUS_IDENTIFIER,
    initialContent: String = "",
    initialExpiration: Long? = null,
    initialReferenceUrl: String = "",
    initialStatuses: Map<String, StatusComposerValue> = emptyMap(),
    categorySelectionEnabled: Boolean = true,
    onDelete: ((String) -> Unit)? = null,
) {
    DismissKeyboardOnLeave()
    val initialCategoryOption = StatusCategoryOption.forIdentifier(initialStatusTag)
    var selectedCategoryOption by remember(initialStatusTag) { mutableStateOf(initialCategoryOption) }
    var customStatusTag by remember(initialStatusTag) {
        mutableStateOf(if (initialCategoryOption == StatusCategoryOption.Custom) initialStatusTag else "")
    }
    // シートを開いた時点の値を編集元として固定し、購読更新で入力中の下書きを上書きしない。
    val statusValues = remember(initialStatusTag) {
        initialStatuses.toMutableMap().apply {
            if (initialStatusTag !in this &&
                (initialContent.isNotBlank() || initialExpiration != null || initialReferenceUrl.isNotBlank())
            ) {
                put(
                    initialStatusTag,
                    StatusComposerValue(initialContent, initialExpiration, initialReferenceUrl),
                )
            }
        }.toMap()
    }
    val initialValue = statusValues[initialStatusTag] ?: StatusComposerValue()
    var draftsByIdentifier by remember(statusValues) {
        mutableStateOf<Map<String, StatusComposerFields>>(emptyMap())
    }
    var content by remember(initialValue) { mutableStateOf(initialValue.content) }
    var referenceUrl by remember(initialValue) { mutableStateOf(initialValue.referenceUrl) }
    var showReferenceUrl by remember(initialValue) { mutableStateOf(initialValue.referenceUrl.isNotBlank()) }
    var selectedExpirationOption by remember(initialValue) {
        mutableStateOf(
            if (initialValue.expiration == null) {
                StatusExpirationOption.NoExpiration
            } else {
                StatusExpirationOption.Custom
            },
        )
    }
    var customExpiration by remember(initialValue) {
        mutableStateOf(initialValue.expiration ?: Clock.System.now().epochSeconds + DAY_SECONDS)
    }
    var showExpirationOptions by remember { mutableStateOf(false) }
    var showCustomDatePicker by remember { mutableStateOf(false) }
    var showCustomTimePicker by remember { mutableStateOf(false) }
    var customDateMillis by remember { mutableStateOf(customExpiration.toUtcDateMillis()) }
    var isDeleting by remember { mutableStateOf(false) }
    val contentFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val dismissKeyboard = rememberDismissKeyboard()
    val selectedExpirationLabel = when (selectedExpirationOption) {
        StatusExpirationOption.Custom -> "カスタム（${formatTimestamp(customExpiration)}）"
        else -> selectedExpirationOption.label
    }
    val selectedIdentifier = selectedCategoryOption.identifier(customStatusTag)
    val selectedStatusExists = selectedIdentifier.isNotBlank() && statusValues.containsKey(selectedIdentifier)
    val resolvedActionLabel = actionLabel ?: if (selectedStatusExists) "保存" else "追加"
    val contentLabel = when (selectedCategoryOption) {
        StatusCategoryOption.General -> "いまの気分や近況"
        StatusCategoryOption.Music -> "聴いている音楽"
        StatusCategoryOption.Custom -> "ステータス"
    }
    val contentPlaceholder = when (selectedCategoryOption) {
        StatusCategoryOption.General -> "例：開発中です 🐦"
        StatusCategoryOption.Music -> "例：曲名 — アーティスト"
        StatusCategoryOption.Custom -> "内容を入力"
    }
    fun currentFields() = StatusComposerFields(
        content = content,
        expirationOption = selectedExpirationOption,
        customExpiration = customExpiration,
        referenceUrl = referenceUrl,
        showReferenceUrl = showReferenceUrl,
    )
    fun fieldsFor(identifier: String): StatusComposerFields {
        draftsByIdentifier[identifier]?.let { return it }
        val value = statusValues[identifier]
        val expiration = value?.expiration
        return StatusComposerFields(
            content = value?.content.orEmpty(),
            expirationOption = if (expiration == null) {
                StatusExpirationOption.NoExpiration
            } else {
                StatusExpirationOption.Custom
            },
            customExpiration = expiration ?: Clock.System.now().epochSeconds + DAY_SECONDS,
            referenceUrl = value?.referenceUrl.orEmpty(),
            showReferenceUrl = value?.referenceUrl?.isNotBlank() == true,
        )
    }
    fun loadFields(fields: StatusComposerFields) {
        content = fields.content
        referenceUrl = fields.referenceUrl
        showReferenceUrl = fields.showReferenceUrl
        selectedExpirationOption = fields.expirationOption
        customExpiration = fields.customExpiration
        customDateMillis = customExpiration.toUtcDateMillis()
    }
    fun selectCategory(option: StatusCategoryOption) {
        draftsByIdentifier = draftsByIdentifier + (selectedIdentifier to currentFields())
        selectedCategoryOption = option
        val nextIdentifier = option.identifier(customStatusTag)
        loadFields(fieldsFor(nextIdentifier))
    }
    val draft = StatusDraft(
        category = selectedCategoryOption,
        customIdentifier = customStatusTag,
        content = content,
        expirationOption = selectedExpirationOption,
        customExpiration = customExpiration,
        referenceUrl = referenceUrl,
    )
    val canSubmit = draft.isValid(Clock.System.now().epochSeconds)
    val submit = {
        isDeleting = false
        val submission = draft.submission(Clock.System.now().epochSeconds)
        onSubmit(
            submission.identifier,
            submission.content,
            submission.expiration,
            submission.referenceUrl,
        )
    }

    LaunchedEffect(contentFocusRequester) {
        // 編集画面が配置されてからフォーカスし、IME の表示要求を確実に届ける。
        withFrameNanos { }
        contentFocusRequester.requestFocus()
        keyboardController?.show()
    }

    LaunchedEffect(showExpirationOptions) {
        if (showExpirationOptions) {
            // iOS では Popup の生成後に入力側がフォーカスを保持することがあるため、
            // メニューが配置された次のフレームでもキーボードを閉じる。
            withFrameNanos { }
            dismissKeyboard()
        }
    }

    Dialog(
        // 接続待ちを理由にモーダルから出られなくすると、コルーチン自体は停止して
        // いなくてもアプリ全体がフリーズしたように見える。送信はViewModelで継続し、
        // 戻る操作だけは常に許可する。入力・再送信は引き続き下の各controlで禁止する。
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .imePadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IconButton(
                        onClick = onDismiss,
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = if (isPublishing) "閉じる" else "キャンセル",
                        )
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        enabled = !isPublishing && canSubmit,
                        onClick = submit,
                    ) {
                        if (isPublishing && !isDeleting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text(resolvedActionLabel)
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (categorySelectionEnabled) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            StatusCategoryOption.entries.forEach { option ->
                                FilterChip(
                                    selected = selectedCategoryOption == option,
                                    onClick = { selectCategory(option) },
                                    label = { Text(option.label) },
                                    enabled = !isPublishing,
                                )
                            }
                        }
                    }
                    if (selectedCategoryOption == StatusCategoryOption.Custom) {
                        OutlinedTextField(
                            value = customStatusTag,
                            onValueChange = { newStatusTag ->
                                val oldIdentifier = selectedCategoryOption.identifier(customStatusTag)
                                val oldIdentifierWasKnown = oldIdentifier in statusValues ||
                                    (oldIdentifier.isNotBlank() && oldIdentifier in draftsByIdentifier)
                                draftsByIdentifier = draftsByIdentifier + (oldIdentifier to currentFields())
                                customStatusTag = newStatusTag
                                val newIdentifier = selectedCategoryOption.identifier(newStatusTag)
                                if (newIdentifier in draftsByIdentifier || newIdentifier in statusValues) {
                                    loadFields(fieldsFor(newIdentifier))
                                } else if (oldIdentifierWasKnown) {
                                    loadFields(fieldsFor(newIdentifier))
                                }
                            },
                            label = { Text("カテゴリ名") },
                            singleLine = true,
                            enabled = !isPublishing,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    OutlinedTextField(
                        value = content,
                        onValueChange = { content = it },
                        label = { Text(contentLabel) },
                        placeholder = { Text(contentPlaceholder) },
                        minLines = 2,
                        maxLines = 4,
                        enabled = !isPublishing,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(contentFocusRequester),
                        supportingText = {
                            Text(
                                text = "${content.length}文字",
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.End,
                            )
                        },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box {
                            AssistChip(
                                onClick = {
                                    if (!showExpirationOptions) {
                                        dismissKeyboard()
                                    }
                                    showExpirationOptions = !showExpirationOptions
                                },
                                label = { Text(selectedExpirationLabel) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.AccessTime,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                                enabled = !isPublishing,
                            )
                            DropdownMenu(
                                expanded = showExpirationOptions,
                                onDismissRequest = { showExpirationOptions = false },
                                properties = PopupProperties(
                                    focusable = true,
                                    dismissOnBackPress = true,
                                    dismissOnClickOutside = true,
                                ),
                            ) {
                                StatusExpirationOption.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (selectedExpirationOption == option) {
                                                    "✓ ${option.label}"
                                                } else {
                                                    option.label
                                                },
                                            )
                                        },
                                        onClick = {
                                            selectedExpirationOption = option
                                            showExpirationOptions = false
                                            if (option == StatusExpirationOption.Custom) {
                                                customDateMillis = customExpiration.toUtcDateMillis()
                                                showCustomDatePicker = true
                                            }
                                        },
                                    )
                                }
                            }
                        }
                        TextButton(
                            onClick = {
                                dismissKeyboard()
                                showReferenceUrl = !showReferenceUrl
                            },
                            enabled = !isPublishing,
                        ) {
                            Icon(
                                Icons.Default.Link,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text("URLを追加")
                            Icon(
                                if (showReferenceUrl) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    if (showReferenceUrl) {
                        OutlinedTextField(
                            value = referenceUrl,
                            onValueChange = { referenceUrl = it },
                            label = { Text("関連URL（任意）") },
                            singleLine = true,
                            enabled = !isPublishing,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    errorMessage?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (onDelete != null && selectedStatusExists) {
                        TextButton(
                            onClick = {
                                isDeleting = true
                                onDelete(selectedIdentifier)
                            },
                            enabled = !isPublishing,
                        ) {
                            if (isPublishing && isDeleting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            } else {
                                Text("削除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCustomDatePicker) {
        CustomExpirationDatePickerDialog(
            initialDateMillis = customDateMillis,
            onDismiss = { showCustomDatePicker = false },
            onConfirm = { selectedDateMillis ->
                customDateMillis = selectedDateMillis
                showCustomDatePicker = false
                showCustomTimePicker = true
            },
        )
    }

    if (showCustomTimePicker) {
        val initialLocalTime = Instant.fromEpochSeconds(customExpiration)
            .toLocalDateTime(TimeZone.currentSystemDefault())
        CustomExpirationTimePickerDialog(
            initialHour = initialLocalTime.hour,
            initialMinute = initialLocalTime.minute,
            selectedDateMillis = customDateMillis,
            onDismiss = { showCustomTimePicker = false },
            onConfirm = { expiration ->
                customExpiration = expiration
                showCustomTimePicker = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomExpirationDatePickerDialog(
    initialDateMillis: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialDateMillis)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { datePickerState.selectedDateMillis?.let(onConfirm) },
                enabled = datePickerState.selectedDateMillis != null,
            ) {
                Text("次へ")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        },
    ) {
        DatePicker(
            state = datePickerState,
            title = { Text("終了日を選択") },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomExpirationTimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    selectedDateMillis: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    val timePickerState = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = true,
    )
    var errorMessage by remember { mutableStateOf<String?>(null) }

    TimePickerDialog(
        onDismissRequest = onDismiss,
        title = { Text("終了時刻を選択") },
        confirmButton = {
            TextButton(
                onClick = {
                    val expiration = customExpirationEpochSeconds(
                        selectedDateMillis = selectedDateMillis,
                        hour = timePickerState.hour,
                        minute = timePickerState.minute,
                    )
                    if (expiration > Clock.System.now().epochSeconds) {
                        onConfirm(expiration)
                    } else {
                        errorMessage = "未来の時刻を選択してください"
                    }
                },
            ) {
                Text("設定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        },
    ) {
        TimePicker(state = timePickerState)
        errorMessage?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
