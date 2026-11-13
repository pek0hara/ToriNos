package com.nostr.torinos.network

import com.nostr.torinos.util.persistentCacheLog
import com.nostr.torinos.util.logException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

/**
 * メモリ上のキャッシュを端末に保存し、起動時に読み戻す(プロフィール・バッジで共通)。
 *
 * 1. 保存済みアカウントのpubkeyを[pin]へ渡し続ける(追い出しの対象から外し、保存でも優先させる)
 * 2. [storage]から読んで[restore]へ渡し、終わったら[onRestored]を呼ぶ。保存先がなければすぐ呼ぶ
 * 3. [version]が開始時の値から変わったら[WRITE_DELAY_MS]待ち、その間の変更をまとめて[encode]の結果を1回書く
 *
 * [version]は保存すべき変更のたびに増え、[restore]では増えないこと。購読前の変更も開始時の値との比較で拾う。
 */
internal class CachePersistence(
    private val name: String,
    private val storage: LocalTextStore?,
    private val scope: CoroutineScope,
    private val accountPubkeys: Flow<Set<String>>,
    private val pin: (Set<String>) -> Unit,
    private val version: StateFlow<Long>,
    private val restore: (String) -> Unit,
    private val encode: () -> String,
    private val onRestored: () -> Unit = {},
    /** ログ用の件数の説明。 */
    private val describe: () -> String = { "" },
) {
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch { accountPubkeys.collect(pin) }
        val storage = storage ?: return onRestored()
        val startVersion = version.value
        scope.launch {
            try {
                read(storage)
            } finally {
                onRestored()
            }
            writeChanges(storage, startVersion)
        }
    }

    private suspend fun read(storage: LocalTextStore) {
        try {
            persistentCacheLog { "$name restore start" }
            val readStart = TimeSource.Monotonic.markNow()
            val content = storage.read()
            val readMillis = readStart.elapsedNow().inWholeMilliseconds
            val restoreStart = TimeSource.Monotonic.markNow()
            content?.let(restore)
            persistentCacheLog {
                "$name restore done bytes=${content?.length ?: "none"} readMs=$readMillis " +
                    "restoreMs=${restoreStart.elapsedNow().inWholeMilliseconds} ${describe()}"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logException(name, e, "Failed to restore cache")
        }
    }

    private suspend fun writeChanges(storage: LocalTextStore, startVersion: Long) {
        var written = startVersion
        while (true) {
            version.first { it != written }
            delay(WRITE_DELAY_MS)
            // 待っている間の変更は、これから書く内容に含まれる。書き込み中の変更は次の周で書く。
            written = version.value
            try {
                val encodeStart = TimeSource.Monotonic.markNow()
                val content = encode()
                val encodeMillis = encodeStart.elapsedNow().inWholeMilliseconds
                val writeStart = TimeSource.Monotonic.markNow()
                storage.write(content)
                persistentCacheLog {
                    "$name write bytes=${content.length} encodeMs=$encodeMillis " +
                        "writeMs=${writeStart.elapsedNow().inWholeMilliseconds} ${describe()}"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logException(name, e, "Failed to save cache")
            }
        }
    }

    companion object {
        const val WRITE_DELAY_MS = 3_000L
    }
}
