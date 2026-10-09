package com.nostr.torinos.ui.article

import androidx.compose.foundation.clickable

import androidx.compose.material3.InputChipDefaults

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.nostr.torinos.ui.components.avoidStaleAccessibilityFocus

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ArticleTagEditor(
    topicsInput: String,
    hasMatometrTag: Boolean,
    enabled: Boolean,
    draftKey: String?,
    onTopicsChange: (String) -> Unit,
) {
    var expanded by rememberSaveable(draftKey) { mutableStateOf(false) }
    var entry by rememberSaveable(draftKey, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    var error by remember(draftKey) { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    // Persist keystrokes in topicsInput too, so publishing, leaving, or switching tabs cannot lose a pending tag.
    val committedInput = articleCommittedTopicsInput(topicsInput, entry.text)
    val committed = articlePublicationTopics(committedInput, hasMatometrTag)
    val topics = committed

    fun update(value: TextFieldValue, commit: Boolean) {
        val combined = (committed + value.text).filter { it.isNotBlank() }.joinToString(" ")
        val count = parseArticleTopics(combined, ARTICLE_TOPICS_MAX_COUNT + 1).size
        error = when {
            combined.length > ARTICLE_TOPICS_INPUT_MAX_LENGTH -> "タグの入力は合計${ARTICLE_TOPICS_INPUT_MAX_LENGTH}文字までです"
            count > ARTICLE_TOPICS_MAX_COUNT -> "タグは${ARTICLE_TOPICS_MAX_COUNT}個まで追加できます"
            else -> null
        }
        if (error != null) return
        onTopicsChange(if (commit) parseArticleTopics(combined).joinToString(" ") else combined)
        entry = if (commit) TextFieldValue() else value
    }

    LaunchedEffect(expanded) {
        if (expanded) focus.requestFocus()
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            topics.forEach { tag ->
                InputChip(
                    selected = false,
                    onClick = {},
                    enabled = enabled,
                    label = { Text("#$tag") },
                    // A bare icon keeps the chip at its normal height; an IconButton would make it 48dp tall.
                    trailingIcon = {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "#$tag を削除",
                            modifier = Modifier.size(InputChipDefaults.IconSize).clickable(enabled = enabled) {
                                onTopicsChange((committed.filterNot { it == tag } + entry.text)
                                    .filter { it.isNotBlank() }.joinToString(" "))
                                error = null
                            },
                        )
                    },
                )
            }
            if (!expanded) {
                TextButton(onClick = { expanded = true }, enabled = enabled) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("タグを追加")
                }
            }
        }
        if (expanded) {
            OutlinedTextField(
                value = entry,
                onValueChange = { value ->
                    // A space in an active Japanese conversion must not confirm the tag.
                    update(value, value.composition == null && value.text.any { it.isWhitespace() || it == ',' || it == '、' })
                },
                placeholder = { Text("タグを追加") },
                prefix = { Text("#") },
                // Confirming is the add button here, the keyboard's done key, or a space or comma.
                trailingIcon = {
                    IconButton(onClick = { update(entry, true) }, enabled = enabled && entry.text.isNotBlank()) {
                        Icon(Icons.Default.Add, contentDescription = "タグを追加")
                    }
                },
                supportingText = error?.let { { Text(it) } },
                isError = error != null,
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { update(entry, true) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).avoidStaleAccessibilityFocus(),
            )
        }
    }
}

internal fun articleCommittedTopicsInput(value: String, pending: String): String =
    if (pending.isNotEmpty() && (value == pending || value.endsWith(" $pending")))
        value.dropLast(pending.length).trimEnd()
    else value
