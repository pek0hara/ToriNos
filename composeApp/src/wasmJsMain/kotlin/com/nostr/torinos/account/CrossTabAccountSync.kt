package com.nostr.torinos.account

import com.nostr.torinos.util.logException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private val crossTabSyncScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * 別のタブでのログイン・ログアウト・切り替え・削除に、このタブのセッションを合わせる。
 *
 * localStorage の `storage` イベントは書き換えたタブ以外にだけ届く。1回の操作で複数のキーが変わり、
 * 途中の状態を読むと誤るので、最後の変更から少し待ってから一度だけ読み直す。
 */
internal fun installCrossTabAccountSync() {
    installStorageListener(ACCOUNT_KEY_PREFIX, DEBOUNCE_MILLIS) {
        crossTabSyncScope.launch {
            AccountSessions.manager.syncWithStorage().onFailure {
                logException("CrossTabAccountSync", it, "Failed to follow another tab")
            }
        }
    }
}

// BrowserKeyStore が使うキーはすべてこの接頭辞で始まる。
private const val ACCOUNT_KEY_PREFIX = "torinos_web_"
private const val DEBOUNCE_MILLIS = 150

private fun installStorageListener(prefix: String, debounceMillis: Int, onChange: () -> Unit) {
    js(
        """
        (() => {
            let timer = 0;
            window.addEventListener('storage', (event) => {
                if (event.storageArea !== window.localStorage) return;
                // key が null なのは localStorage.clear()。
                if (event.key !== null && !event.key.startsWith(prefix)) return;
                clearTimeout(timer);
                timer = setTimeout(() => onChange(), debounceMillis);
            });
        })()
        """,
    )
}
