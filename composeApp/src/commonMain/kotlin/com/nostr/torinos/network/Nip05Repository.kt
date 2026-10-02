package com.nostr.torinos.network

import com.nostr.torinos.createHttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A domain association, not proof of a person's identity. */
enum class Nip05Status(val message: String) {
    Unchecked("未確認"),
    Checking("確認中…"),
    Verified("NIP-05確認済み"),
    InvalidAddress("アドレスの形式が正しくありません"),
    Mismatch("アドレスと公開鍵が一致しません"),
    NetworkError("確認できませんでした。通信状態やドメインの設定を確認して再試行してください"),
}

internal data class Nip05Address(val name: String, val domain: String) {
    val url: String get() = "https://$domain/.well-known/nostr.json?name=$name"
}

internal fun parseNip05Address(value: String): Nip05Address? {
    val parts = value.trim().split('@')
    if (parts.size != 2 || !parts[0].matches(Regex("[a-z0-9_.-]+"))) return null
    val domain = parts[1].lowercase()
    if (domain.length > 253 || !domain.contains('.')) return null
    if (domain.split('.').any {
        it.length !in 1..63 || !it.matches(Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?"))
    }) return null
    return Nip05Address(parts[0], domain)
}

@Serializable
private data class Nip05Document(val names: Map<String, String>)

internal suspend fun verifyNip05(
    address: String,
    pubkey: String,
    fetch: suspend (String) -> String,
): Nip05Status {
    val parsed = parseNip05Address(address) ?: return Nip05Status.InvalidAddress
    return try {
        val document = Json { ignoreUnknownKeys = true }.decodeFromString<Nip05Document>(fetch(parsed.url))
        val key = document.names[parsed.name]
        if (key != null && key.matches(Regex("[0-9a-f]{64}")) && key == pubkey) {
            Nip05Status.Verified
        } else {
            Nip05Status.Mismatch
        }
    } catch (_: TimeoutCancellationException) {
        Nip05Status.NetworkError
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        Nip05Status.NetworkError
    }
}

/** Verification outcomes are shared by all screens and keyed by both address and public key. */
internal class Nip05VerificationStore(
    private val scope: CoroutineScope,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val verifyAddress: suspend (String, String) -> Nip05Status,
) {
    private data class Entry(val status: Nip05Status, val expiresAt: Long)
    private val entries = MutableStateFlow<Map<Pair<String, String>, Entry>>(emptyMap())
    private val mutex = Mutex()
    private val inFlight = mutableMapOf<Pair<String, String>, Deferred<Nip05Status>>()

    fun observe(address: String, pubkey: String): Flow<Nip05Status> {
        val key = address.trim() to pubkey
        return entries.map { it[key]?.status ?: Nip05Status.Unchecked }.distinctUntilChanged()
    }

    suspend fun verify(address: String, pubkey: String, forceRefresh: Boolean = false): Nip05Status {
        val key = address.trim() to pubkey
        val deferred = mutex.withLock {
            inFlight[key]?.let { return@withLock it }
            val existing = entries.value[key]
            if (!forceRefresh && existing != null && now() < existing.expiresAt) {
                return existing.status
            }
            update(key, Entry(Nip05Status.Checking, 0L))
            scope.async {
                try {
                    val result = verifyAddress(key.first, key.second)
                    mutex.withLock {
                        val ttl = if (result == Nip05Status.NetworkError) 30_000L else 5 * 60 * 1_000L
                        update(key, Entry(result, now() + ttl))
                    }
                    result
                } finally {
                    mutex.withLock {
                        inFlight.remove(key)
                        if (entries.value[key]?.status == Nip05Status.Checking) {
                            update(key, Entry(Nip05Status.Unchecked, 0L))
                        }
                    }
                }
            }.also { inFlight[key] = it }
        }
        return deferred.await()
    }

    /** Called under mutex; keep the most recently updated 200 outcomes. */
    private fun update(key: Pair<String, String>, entry: Entry) {
        val updated = (entries.value - key) + (key to entry)
        entries.value = if (updated.size > 200) updated - updated.keys.first() else updated
    }
}

object Nip05Repository {
    private val client = createHttpClient().config { followRedirects = false }
    private val store = Nip05VerificationStore(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    ) { address, pubkey ->
        verifyNip05(address, pubkey) { url ->
            withTimeout(5_000L) {
                val response = client.get(url)
                check(response.status.isSuccess()) { "HTTP ${response.status.value}" }
                response.bodyAsText()
            }
        }
    }

    fun observe(address: String, pubkey: String): Flow<Nip05Status> = store.observe(address, pubkey)

    suspend fun verify(address: String, pubkey: String, forceRefresh: Boolean = false): Nip05Status =
        store.verify(address, pubkey, forceRefresh)
}
