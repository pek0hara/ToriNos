package com.nostr.torinos.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

@Composable
fun rememberSyncedTextFieldValue(text: String): MutableState<TextFieldValue> {
    val value = remember { mutableStateOf(TextFieldValue(text)) }

    LaunchedEffect(text) {
        if (text != value.value.text) {
            val selection = value.value.selection
            value.value = TextFieldValue(
                text = text,
                selection = TextRange(
                    start = selection.start.coerceAtMost(text.length),
                    end = selection.end.coerceAtMost(text.length),
                ),
            )
        }
    }

    return value
}
