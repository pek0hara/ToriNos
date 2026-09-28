package com.nostr.torinos.ui.setup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.HtmlElementView
import kotlinx.browser.document
import org.w3c.dom.HTMLInputElement

// Compose の入力欄はブラウザの入力要素を持たず、スマホで長押しの「ペースト」が出ない。
// また貼り付けに使う Clipboard API は http の LAN 内などでは使えない。
// ブラウザ標準の input 要素なら、OS の貼り付けメニューとパスワードマネージャーがそのまま使える。
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal actual fun PrivateKeyInputField(
    value: String,
    onValueChange: (String) -> Unit,
    error: String?,
    modifier: Modifier,
) {
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val colors = MaterialTheme.colorScheme
    val borderColor = if (error != null) colors.error else colors.outline

    Column(modifier = modifier) {
        HtmlElementView(
            factory = {
                (document.createElement("input") as HTMLInputElement).apply {
                    type = "password"
                    placeholder = PrivateKeyInputLabel
                    setAttribute("autocomplete", "current-password")
                    setAttribute("autocapitalize", "none")
                    setAttribute("autocorrect", "off")
                    setAttribute("spellcheck", "false")
                    setAttribute("aria-label", PrivateKeyInputLabel)
                    // iOS Safari は 16px 未満の入力欄にフォーカスすると画面を拡大するため 16px にする。
                    style.cssText = "box-sizing: border-box; width: 100%; height: 100%; margin: 0; " +
                        "padding: 0 16px; font-size: 16px; border-radius: 4px; outline: none;"
                    addEventListener("input") { currentOnValueChange(this.value) }
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            update = { input ->
                if (input.value != value) input.value = value
                input.style.color = colors.onSurface.toCss()
                input.style.backgroundColor = colors.surface.toCss()
                input.style.border = "1px solid ${borderColor.toCss()}"
            },
        )
        if (error != null) {
            Text(
                text = error,
                color = colors.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp),
            )
        }
    }
}

private fun Color.toCss(): String =
    "rgba(${(red * 255).toInt()}, ${(green * 255).toInt()}, ${(blue * 255).toInt()}, $alpha)"
