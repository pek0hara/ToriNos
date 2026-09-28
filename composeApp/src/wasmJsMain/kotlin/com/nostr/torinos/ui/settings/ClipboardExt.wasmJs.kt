package com.nostr.torinos.ui.settings

import androidx.compose.ui.platform.Clipboard
import kotlin.js.Promise
import kotlinx.coroutines.await

actual suspend fun Clipboard.setPlainText(text: String) {
    if (isClipboardApiAvailable()) {
        writeClipboardText(text).await<JsAny?>()
    } else {
        // LAN 内の http など安全でないコンテキストでは Clipboard API が無いため、旧来の方法でコピーする。
        check(copyWithExecCommand(text)) { "クリップボードにコピーできませんでした" }
    }
}

private fun isClipboardApiAvailable(): Boolean =
    js("window.isSecureContext === true && !!(navigator.clipboard && navigator.clipboard.writeText)")

private fun writeClipboardText(text: String): Promise<JsAny?> =
    js("navigator.clipboard.writeText(text)")

private fun copyWithExecCommand(text: String): Boolean =
    js(
        """
        (() => {
            const active = document.activeElement;
            const area = document.createElement('textarea');
            area.value = text;
            area.setAttribute('readonly', '');
            area.style.position = 'fixed';
            area.style.opacity = '0';
            document.body.appendChild(area);
            area.select();
            area.setSelectionRange(0, text.length);
            let copied = false;
            try { copied = document.execCommand('copy'); } catch (e) { copied = false; }
            document.body.removeChild(area);
            if (active && active.focus) active.focus();
            return copied;
        })()
        """,
    )
