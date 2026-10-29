package com.nostr.torinos.ui.zap

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.InputChipDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nostr.torinos.ui.components.avoidStaleAccessibilityFocus
import com.nostr.torinos.zap.DEFAULT_ZAP_AMOUNTS
import com.nostr.torinos.zap.MAX_ZAP_AMOUNT_CHOICES
import com.nostr.torinos.zap.ZapAmountEdit
import com.nostr.torinos.zap.addZapAmount
import com.nostr.torinos.zap.removeZapAmount

/**
 * ⚡を押したときに出す額の選択肢の編集。額はチップで並べ、×で外し、「額を追加」で1つずつ足す。
 * 入力中の文字は保存値と切り離して持つので、打っている途中で書き換わらない。
 * 記事のタグ入力（ArticleTagEditor）と同じ操作にそろえている。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ZapAmountEditor(
    amounts: List<Long>,
    onAmountsChange: (List<Long>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var entry by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val canAdd = amounts.size < MAX_ZAP_AMOUNT_CHOICES

    fun commit() {
        when (val result = addZapAmount(amounts, entry)) {
            is ZapAmountEdit.Updated -> {
                onAmountsChange(result.amounts)
                entry = ""
                error = null
                // 上限に達したら入力欄を閉じる。まだ足せるなら続けて打てるよう開いたままにする。
                if (result.amounts.size >= MAX_ZAP_AMOUNT_CHOICES) adding = false
            }
            is ZapAmountEdit.Rejected -> error = result.reason
        }
    }

    LaunchedEffect(adding) { if (adding) focus.requestFocus() }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("⚡を押したときに出す額", style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            amounts.forEach { sats ->
                val removable = amounts.size > 1
                InputChip(
                    selected = false,
                    onClick = {},
                    label = { Text("⚡${formatSats(sats)}") },
                    // 最後の1つは外せない（選択肢が空だと⚡から送れなくなるため）。
                    trailingIcon = if (removable) {
                        {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "${formatSats(sats)} sats を削除",
                                modifier = Modifier.size(InputChipDefaults.IconSize).clickable {
                                    removeZapAmount(amounts, sats)?.let(onAmountsChange)
                                    error = null
                                },
                            )
                        }
                    } else null,
                )
            }
            if (canAdd && !adding) {
                TextButton(onClick = { adding = true }) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("額を追加")
                }
            }
        }
        if (adding && canAdd) {
            OutlinedTextField(
                value = entry,
                onValueChange = { text ->
                    entry = text.filter(Char::isDigit).take(7)
                    error = null
                },
                placeholder = { Text("例: 500") },
                suffix = { Text("sats") },
                trailingIcon = {
                    IconButton(onClick = ::commit, enabled = entry.isNotEmpty()) {
                        Icon(Icons.Default.Add, contentDescription = "額を追加")
                    }
                },
                supportingText = {
                    Text(error ?: "1〜1,000,000 sats、${MAX_ZAP_AMOUNT_CHOICES} 個まで")
                },
                isError = error != null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).avoidStaleAccessibilityFocus(),
            )
            TextButton(onClick = { adding = false; entry = ""; error = null }) { Text("閉じる") }
        }
        if (amounts != DEFAULT_ZAP_AMOUNTS) {
            TextButton(onClick = { onAmountsChange(DEFAULT_ZAP_AMOUNTS); error = null }) {
                Text("初期値（${DEFAULT_ZAP_AMOUNTS.joinToString(" / ") { formatSats(it) }}）に戻す")
            }
        }
    }
}
