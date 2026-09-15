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
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StatusComposerSheet(
    title: String,
    actionLabel: String,
    isPublishing: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSubmit: (statusTag: String, content: String, expiration: Long?, referenceUrl: String?) -> Unit,
    initialStatusTag: String = GENERAL_STATUS_TAG,
    initialContent: String = "",
    initialExpiration: Long? = null,
    initialReferenceUrl: String = "",
    onDelete: (() -> Unit)? = null,
) {
    val initialCategoryOption = StatusCategoryOption.forTag(initialStatusTag)
    var selectedCategoryOption by remember(initialStatusTag) { mutableStateOf(initialCategoryOption) }
    var customStatusTag by remember(initialStatusTag) {
        mutableStateOf(if (initialCategoryOption == StatusCategoryOption.Custom) initialStatusTag else "")
    }
    var content by remember(initialContent) { mutableStateOf(initialContent) }
    var referenceUrl by remember(initialReferenceUrl) { mutableStateOf(initialReferenceUrl) }
    var showReferenceUrl by remember(initialReferenceUrl) { mutableStateOf(initialReferenceUrl.isNotBlank()) }
    var selectedExpirationOption by remember(initialExpiration) {
        mutableStateOf(
            if (initialExpiration == null) {
                StatusExpirationOption.NoExpiration
            } else {
                StatusExpirationOption.Custom
            },
        )
    }
    var customExpiration by remember(initialExpiration) {
        mutableStateOf(initialExpiration ?: Clock.System.now().epochSeconds + DAY_SECONDS)
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
    val canSubmit = content.isNotBlank() &&
        (selectedCategoryOption != StatusCategoryOption.Custom || customStatusTag.isNotBlank()) &&
        (selectedExpirationOption != StatusExpirationOption.Custom ||
            customExpiration > Clock.System.now().epochSeconds)
    val submit = {
        isDeleting = false
        val expiration = when (selectedExpirationOption) {
            StatusExpirationOption.TwentyFourHours ->
                Clock.System.now().epochSeconds + DAY_SECONDS
            StatusExpirationOption.Custom -> customExpiration
            StatusExpirationOption.NoExpiration -> null
        }
        onSubmit(
            selectedCategoryOption.tag(customStatusTag),
            content,
            expiration,
            referenceUrl,
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
        onDismissRequest = { if (!isPublishing) onDismiss() },
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
                        enabled = !isPublishing,
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "キャンセル")
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
                            Text(actionLabel)
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StatusCategoryOption.entries.forEach { option ->
                            FilterChip(
                                selected = selectedCategoryOption == option,
                                onClick = { selectedCategoryOption = option },
                                label = { Text(option.label) },
                                enabled = !isPublishing,
                            )
                        }
                    }
                    if (selectedCategoryOption == StatusCategoryOption.Custom) {
                        OutlinedTextField(
                            value = customStatusTag,
                            onValueChange = { customStatusTag = it },
                            label = { Text("カテゴリ名") },
                            singleLine = true,
                            enabled = !isPublishing,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    OutlinedTextField(
                        value = content,
                        onValueChange = { content = it },
                        label = { Text("いまの気分や近況") },
                        placeholder = { Text("例：開発中です 🐦") },
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
                    if (onDelete != null) {
                        TextButton(
                            onClick = {
                                isDeleting = true
                                onDelete()
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

private fun Long.toUtcDateMillis(): Long {
    val date = Instant.fromEpochSeconds(this)
        .toLocalDateTime(TimeZone.currentSystemDefault())
        .date
    return LocalDateTime(date.year, date.month, date.day, 0, 0)
        .toInstant(TimeZone.UTC)
        .toEpochMilliseconds()
}

private fun customExpirationEpochSeconds(
    selectedDateMillis: Long,
    hour: Int,
    minute: Int,
): Long {
    val date: LocalDate = Instant.fromEpochMilliseconds(selectedDateMillis)
        .toLocalDateTime(TimeZone.UTC)
        .date
    return LocalDateTime(date.year, date.month, date.day, hour, minute)
        .toInstant(TimeZone.currentSystemDefault())
        .epochSeconds
}

private enum class StatusCategoryOption(
    val label: String,
    private val fixedTag: String?,
) {
    General("💬 一般", GENERAL_STATUS_TAG),
    Music("♫ 音楽", MUSIC_STATUS_TAG),
    Custom("その他", null);

    fun tag(customStatusTag: String): String = fixedTag ?: customStatusTag.trim()

    companion object {
        fun forTag(statusTag: String): StatusCategoryOption = when {
            statusTag.equals(GENERAL_STATUS_TAG, ignoreCase = true) -> General
            statusTag.equals(MUSIC_STATUS_TAG, ignoreCase = true) -> Music
            else -> Custom
        }
    }
}

private enum class StatusExpirationOption(
    val label: String,
) {
    TwentyFourHours("24時間後"),
    Custom("カスタム"),
    NoExpiration("終了なし"),
}

private const val DAY_SECONDS = 24 * 60 * 60L
private const val GENERAL_STATUS_TAG = "general"
private const val MUSIC_STATUS_TAG = "music"
