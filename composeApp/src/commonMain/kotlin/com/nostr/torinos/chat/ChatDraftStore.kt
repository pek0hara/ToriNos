package com.nostr.torinos.chat

import com.nostr.torinos.network.LocalSettingsStorage
import com.nostr.torinos.util.logException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/** メンションの候補から選んだ人。送るときに本文に `@表示名` が残っている人だけを宛先にするので、名前も残す。 */
@Serializable
data class ChatDraftMention(val pubkey: String, val name: String)

/** チャットの入力欄の下書き。[updatedAt] はエポック秒。 */
@Serializable
data class ChatDraft(
    val text: String,
    val mentions: List<ChatDraftMention> = emptyList(),
    val updatedAt: Long = 0,
)

/** 下書きの保存先。アカウントごとに1つの文字列を読み書きする。 */
internal interface ChatDraftStorage {
    suspend fun read(key: String): String?
    suspend fun write(key: String, value: String?)
}

/** Android は DataStore、iOS は UserDefaults、Web は localStorage。 */
internal object SettingsChatDraftStorage : ChatDraftStorage {
    override suspend fun read(key: String) = LocalSettingsStorage.getString(key)
    override suspend fun write(key: String, value: String?) = LocalSettingsStorage.putString(key, value)
}

/** 端末に書かず、セッションの間だけ持つ（DM の下書き。開封した DM と同じくメモリーにだけ置く）。 */
internal class MemoryChatDraftStorage : ChatDraftStorage {
    private var rows = mapOf<String, String>()
    override suspend fun read(key: String) = rows[key]
    override suspend fun write(key: String, value: String?) {
        rows = if (value == null) rows - key else rows + (key to value)
    }
}

internal fun chatDraftStorageKey(owner: String) = "chat.drafts.$owner"

private val draftJson = Json { ignoreUnknownKeys = true }

/**
 * チャットの入力欄の下書きを、部屋（またはDMの会話）ごとに端末へ残す。
 *
 * 画面を離れても、アプリが止められても、戻ったときに続きから書けるようにする。
 * 入力のたびにメモリの値はすぐ変え、端末へは入力が [saveDelayMillis] 止まってから書く。
 * 画面を離れるときやアプリが裏に回るときは [flush] ですぐ書く。
 * 古いもの（[maxAgeSeconds] より前）と、新しい順に [maxDrafts] 件を超えた分は書くときに捨てる。
 */
class ChatDraftStore internal constructor(
    private val owner: String,
    private val scope: CoroutineScope,
    private val storage: ChatDraftStorage = SettingsChatDraftStorage,
    private val now: () -> Long = { Clock.System.now().epochSeconds },
    private val saveDelayMillis: Long = 500,
    private val maxDrafts: Int = 100,
    private val maxAgeSeconds: Long = 30L * 24 * 60 * 60,
) {
    private val _drafts = MutableStateFlow<Map<String, ChatDraft>>(emptyMap())
    /** 部屋ごとの下書き。チャット一覧で「下書き」を出すのにも使える。 */
    val drafts: StateFlow<Map<String, ChatDraft>> = _drafts.asStateFlow()
    private val loaded = MutableStateFlow(false)
    /** 読み込みが終わる前に書き換えた部屋。読み込んだ値より手元の値を優先する。 */
    private val touched = mutableSetOf<String>()
    private val writeLock = Mutex()
    private var saveJob: Job? = null

    init {
        scope.launch { load() }
    }

    private suspend fun load() {
        val stored = try {
            storage.read(chatDraftStorageKey(owner))?.let { draftJson.decodeFromString<Map<String, ChatDraft>>(it) }.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logException("ChatDraftStore", e, "下書きを読み込めません")
            emptyMap()
        }
        _drafts.update { current ->
            val kept = stored.filterKeys { it !in touched }
            (kept + current.filterKeys { it in touched }).filterValues { it.text.isNotBlank() }
        }
        loaded.value = true
    }

    /** 読み込み済みなら、その部屋の下書き。 */
    fun draft(key: String): ChatDraft? = _drafts.value[key]

    /** 端末からの読み込みを待ってから、その部屋の下書きを返す。 */
    suspend fun awaitDraft(key: String): ChatDraft? = awaitAll()[key]

    suspend fun awaitAll(): Map<String, ChatDraft> {
        loaded.first { it }
        return _drafts.value
    }

    /** 入力欄が変わった。空なら下書きを消す。 */
    fun update(key: String, text: String, mentions: List<ChatDraftMention> = emptyList()) {
        if (!loaded.value) touched += key
        _drafts.update { current ->
            if (text.isBlank()) {
                if (key !in current && loaded.value) return
                current - key
            } else {
                current + (key to ChatDraft(text, mentions.filter { "@${it.name}" in text }, now()))
            }
        }
        scheduleSave()
    }

    /** 送ったので下書きを消す。 */
    fun clear(key: String) = update(key, "")

    /** 待たずに端末へ書く（画面を離れるとき、アプリが裏に回るとき）。 */
    fun flush() {
        saveJob?.cancel()
        saveJob = scope.launch { save() }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(saveDelayMillis)
            save()
        }
    }

    private suspend fun save() {
        loaded.first { it }
        writeLock.withLock {
            val cutoff = now() - maxAgeSeconds
            val kept = _drafts.value.filterValues { it.updatedAt >= cutoff }
                .entries.sortedByDescending { it.value.updatedAt }.take(maxDrafts)
                .associate { it.key to it.value }
            if (kept.size != _drafts.value.size) _drafts.update { current -> current.filterKeys { it in kept } }
            try {
                storage.write(chatDraftStorageKey(owner), if (kept.isEmpty()) null else draftJson.encodeToString(kept))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logException("ChatDraftStore", e, "下書きを保存できません")
            }
        }
    }
}
