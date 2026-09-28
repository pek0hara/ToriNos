package com.nostr.torinos.emoji

import com.nostr.torinos.util.appLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * アカウントセッションが所有するカスタム絵文字設定。
 *
 * 変更はすべて1本のコルーチンが受け取った順に適用する。読み込み前に受けた変更は読み込み後に適用するので、
 * 起動直後の操作も失われない。保存は別の1本のコルーチンが最新の状態だけを順に書く。
 */
class CustomEmojiRepository internal constructor(
    private val pubkey: String,
    private val scope: CoroutineScope,
    private val storage: EmojiPreferencesStorage = EmojiPreferencesStorage(),
) {
    private val _preferences = MutableStateFlow(EmojiPreferences())
    val preferences: StateFlow<EmojiPreferences> = _preferences.asStateFlow()

    private val _isLoaded = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    private val _localChanges = MutableStateFlow(0L)

    /** 同期対象（セット・お気に入り）を端末で変更するたびに増える。同期処理が購読して送信を予約する。 */
    internal val localChanges: StateFlow<Long> = _localChanges.asStateFlow()

    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val saveRequests = MutableStateFlow<EmojiPreferences?>(null)
    private var started = false

    private sealed interface Command {
        class Local(val transform: (EmojiPreferences) -> EmojiPreferences) : Command
        class Remote(
            val transform: (EmojiPreferences) -> EmojiPreferences,
            val done: CompletableDeferred<EmojiPreferences>,
        ) : Command
    }

    internal fun start() {
        if (started) return
        started = true
        scope.launch {
            val loaded = runCatching { storage.load(pubkey) }
                .onFailure { if (it is CancellationException) throw it }
                .onFailure { appLog("[CustomEmojiRepository] load failed: ${it.message}") }
                .getOrDefault(EmojiPreferences())
            _preferences.value = loaded
            _isLoaded.value = true
            for (command in commands) {
                val before = _preferences.value
                val after = when (command) {
                    is Command.Local -> command.transform(before)
                    is Command.Remote -> command.transform(before).also { command.done.complete(it) }
                }
                if (after === before) continue
                _preferences.value = after
                saveRequests.value = after
                if (command is Command.Local && after.revision != before.revision) {
                    _localChanges.value += 1
                }
            }
        }
        scope.launch {
            saveRequests.filterNotNull().collect { snapshot ->
                runCatching { storage.save(pubkey, snapshot) }
                    .onFailure { if (it is CancellationException) throw it }
                    .onFailure { appLog("[CustomEmojiRepository] save failed: ${it.message}") }
            }
        }
    }

    internal suspend fun awaitLoaded() {
        isLoaded.first { it }
    }

    fun registerSet(set: RegisteredEmojiSet) = local { it.registerSet(set) }

    fun unregisterSet(address: EmojiSetAddress) = local { it.unregisterSet(address) }

    fun toggleFavorite(emoji: CustomEmoji) = local { it.toggleFavorite(emoji) }

    fun recordUse(reaction: RecentReaction) = local { it.recordUse(reaction) }

    /** リレーの内容を取り込み、適用後の状態を返す。未送信の変更があれば端末側が残る。 */
    internal suspend fun applyRemote(
        favorites: List<CustomEmoji>,
        sets: List<RegisteredEmojiSet>,
        unresolved: Set<EmojiSetAddress>,
    ): EmojiPreferences = remote { it.applyRemote(favorites, sets, unresolved) }

    internal suspend fun markSynced(sentRevision: Long): EmojiPreferences = remote { it.markSynced(sentRevision) }

    private fun local(transform: (EmojiPreferences) -> EmojiPreferences) {
        commands.trySend(Command.Local(transform))
    }

    private suspend fun remote(transform: (EmojiPreferences) -> EmojiPreferences): EmojiPreferences {
        val done = CompletableDeferred<EmojiPreferences>()
        commands.send(Command.Remote(transform, done))
        return done.await()
    }

    internal fun close() {
        commands.close()
    }
}
