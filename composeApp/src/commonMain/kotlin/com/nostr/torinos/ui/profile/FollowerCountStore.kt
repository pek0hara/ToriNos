package com.nostr.torinos.ui.profile

import com.nostr.torinos.network.LocalSettingsStorage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/** 前に数えたフォロワー数。[countedAt] は epoch 秒。 */
@Serializable
data class FollowerCountSnapshot(
    val count: Int,
    val limited: Boolean,
    val countedAt: Long,
)

/** 全部数え終わったときのフォロワーの pubkey。通知の既知フォロワー一覧を補うのに使う。 */
internal data class CompletedFollowerCount(val pubkey: String, val followers: Set<String>)

@Serializable
private data class StoredFollowerCount(val pubkey: String, val snapshot: FollowerCountSnapshot)

/**
 * pubkey ごとのフォロワー数を端末に保存する。フォロワー数は公開情報なのでアカウントに依らず共有する。
 * 数えた順に並べて持ち、[maxEntries] を超えたら古いものから消す。
 */
internal class FollowerCountStore(
    private val readStorage: suspend (String) -> String? = LocalSettingsStorage::getString,
    private val writeStorage: suspend (String, String?) -> Unit = LocalSettingsStorage::putString,
    private val now: () -> Long = { Clock.System.now().epochSeconds },
    private val maxEntries: Int = MAX_ENTRIES,
) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private var loaded = false
    private val mutableCounts = MutableStateFlow<Map<String, FollowerCountSnapshot>>(emptyMap())
    val counts: StateFlow<Map<String, FollowerCountSnapshot>> = mutableCounts.asStateFlow()
    private val mutableCompletedCounts = MutableSharedFlow<CompletedFollowerCount>(extraBufferCapacity = 8)
    val completedCounts: SharedFlow<CompletedFollowerCount> = mutableCompletedCounts.asSharedFlow()

    suspend fun ensureLoaded() {
        mutex.withLock { loadLocked() }
    }

    /** 全部数え終わった結果を残す。[followers] は数えた相手の pubkey（分かるときだけ）。 */
    suspend fun record(pubkey: String, count: Int, limited: Boolean, followers: Set<String> = emptySet()) {
        mutex.withLock {
            loadLocked()
            putLocked(pubkey, FollowerCountSnapshot(count, limited, now()))
        }
        if (followers.isNotEmpty()) mutableCompletedCounts.tryEmit(CompletedFollowerCount(pubkey, followers))
    }

    /**
     * 通知で新しいフォロワーが分かったときに 1 足す。数えた時刻は変えない（その時刻に全部数え、後から足した数）。
     * フォローを外した人はここでは分からないので、正しい数に戻すのは数え直したとき。
     */
    suspend fun recordNewFollower(pubkey: String) {
        mutex.withLock {
            loadLocked()
            val current = mutableCounts.value[pubkey] ?: return
            putLocked(pubkey, current.copy(count = current.count + 1))
        }
    }

    private suspend fun putLocked(pubkey: String, snapshot: FollowerCountSnapshot) {
        val next = LinkedHashMap(mutableCounts.value)
        next.remove(pubkey)
        next[pubkey] = snapshot
        while (next.size > maxEntries) next.remove(next.keys.first())
        mutableCounts.value = next
        val stored = next.map { (key, value) -> StoredFollowerCount(key, value) }
        writeStorage(KEY, json.encodeToString(ListSerializer(StoredFollowerCount.serializer()), stored))
    }

    private suspend fun loadLocked() {
        if (loaded) return
        loaded = true
        val saved = readStorage(KEY) ?: return
        val entries = runCatching {
            json.decodeFromString(ListSerializer(StoredFollowerCount.serializer()), saved)
        }.getOrNull() ?: return
        val restored = LinkedHashMap<String, FollowerCountSnapshot>()
        entries.forEach { restored.remove(it.pubkey); restored[it.pubkey] = it.snapshot }
        // 読み込み前に record されることはない（record も先に読む）ので、そのまま置き換えてよい。
        mutableCounts.value = restored
    }

    companion object {
        private const val KEY = "follower_counts_v1"
        const val MAX_ENTRIES = 200

        val shared: FollowerCountStore by lazy { FollowerCountStore() }
    }
}

/** 数え終わった結果を保存してよいか。0件はリレーが落ちているだけかもしれないので保存しない。 */
internal fun shouldRecordFollowerCount(cancelled: Boolean, count: Int): Boolean = !cancelled && count > 0

/** limit 件ちょうど届いたら、まだ先がある可能性がある。 */
internal fun isFollowerCountLimited(receivedEvents: Int, limit: Int): Boolean = receivedEvents >= limit

internal fun followerCountAgeLabel(countedAt: Long, now: Long): String {
    val elapsed = (now - countedAt).coerceAtLeast(0L)
    return when {
        elapsed < 60L -> "たった今"
        elapsed < 3_600L -> "${elapsed / 60L}分前"
        elapsed < 86_400L -> "${elapsed / 3_600L}時間前"
        else -> "${elapsed / 86_400L}日前"
    }
}

internal sealed interface FollowerStatDisplay {
    /** 保存なし・未取得。「—」を出し、タップで数える。 */
    data object Unknown : FollowerStatDisplay

    /** 保存なしで数えている最中。届いた数をその場で増やす。 */
    data class Counting(val liveCount: Int) : FollowerStatDisplay

    /** 保存あり。数え直し中も途中の数ではなく保存値を出す。 */
    data class Known(
        val count: Int,
        val limited: Boolean,
        val countedAt: Long,
        val refreshing: Boolean,
    ) : FollowerStatDisplay
}

internal fun followerStatDisplay(
    stored: FollowerCountSnapshot?,
    isLoading: Boolean,
    liveCount: Int,
): FollowerStatDisplay = when {
    stored != null -> FollowerStatDisplay.Known(stored.count, stored.limited, stored.countedAt, refreshing = isLoading)
    isLoading -> FollowerStatDisplay.Counting(liveCount)
    else -> FollowerStatDisplay.Unknown
}
