package com.nostr.torinos.chat

import com.nostr.torinos.network.LocalSettingsStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * チャットタブで非表示にしたリレー・コミュニティ（アカウントごと、この端末だけ）。
 * 参加中の一覧（kind 10009）は変えない。切り替えメニューと「すべて」の一覧から外し、読み込みもしない。
 */
object ChatHiddenRelaysStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val flows = mutableMapOf<String, MutableStateFlow<Set<String>>>()

    /** [pubkey] の非表示のリレー。保存した値を読み終えるまでは空。 */
    fun hidden(pubkey: String): StateFlow<Set<String>> = flowFor(pubkey).asStateFlow()

    fun hide(pubkey: String, relayUrl: String) = change(pubkey) { it + normalizeHiddenRelayUrl(relayUrl) }

    fun show(pubkey: String, relayUrl: String) = change(pubkey) { it - normalizeHiddenRelayUrl(relayUrl) }

    private fun change(pubkey: String, transform: (Set<String>) -> Set<String>) {
        val flow = flowFor(pubkey)
        flow.update(transform)
        val value = flow.value
        scope.launch { LocalSettingsStorage.putString(key(pubkey), encodeHiddenRelays(value)) }
    }

    /** 画面（メインスレッド）からだけ呼ぶ。初めてのアカウントでは保存した値を読み始める。 */
    private fun flowFor(pubkey: String): MutableStateFlow<Set<String>> {
        flows[pubkey]?.let { return it }
        val flow = MutableStateFlow(emptySet<String>())
        flows[pubkey] = flow
        scope.launch {
            val stored = decodeHiddenRelays(LocalSettingsStorage.getString(key(pubkey)))
            // 読み終える前に変えていたら、その変更に足す。
            flow.update { it + stored }
        }
        return flow
    }

    private fun key(pubkey: String) = "chat.hiddenRelays.$pubkey"
}

internal fun normalizeHiddenRelayUrl(relayUrl: String): String = relayUrl.trim().trimEnd('/')

internal fun encodeHiddenRelays(relays: Set<String>): String? =
    relays.takeIf { it.isNotEmpty() }?.sorted()?.joinToString("\n")

internal fun decodeHiddenRelays(stored: String?): Set<String> =
    stored.orEmpty().lineSequence().map(::normalizeHiddenRelayUrl).filter { it.isNotEmpty() }.toSet()

/** [relayUrl] が非表示か（末尾の / の有無は区別しない）。 */
internal fun isHiddenRelay(relayUrl: String, hidden: Set<String>): Boolean =
    hidden.isNotEmpty() && normalizeHiddenRelayUrl(relayUrl) in hidden
