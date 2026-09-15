package com.nostr.torinos.network

import com.nostr.torinos.crypto.isValidEvent
import com.nostr.torinos.model.COMMENT_EVENT_KIND
import com.nostr.torinos.model.NoteContext
import com.nostr.torinos.model.NostrEvent
import com.nostr.torinos.model.NostrFilter
import com.nostr.torinos.model.ReplyEventReference
import com.nostr.torinos.model.ReplyTarget
import com.nostr.torinos.model.toReplyTarget
import com.nostr.torinos.model.timelineRootHint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

private const val REPLY_ROOT_FETCH_TIMEOUT_MILLIS = 1_500L

internal fun interface ReplyRootEventFetcher {
    suspend fun fetch(id: String, relayHint: String?): NostrEvent?
}

/** Missing or contradictory NIP-22 root metadata is corrected from the signed root event. */
internal suspend fun resolveReplyTarget(
    event: NostrEvent,
    noteContext: NoteContext,
    rootFetcher: ReplyRootEventFetcher = DefaultReplyRootEventFetcher,
): ReplyTarget? {
    if (noteContext != NoteContext.Timeline || event.kind !in setOf(1, COMMENT_EVENT_KIND)) {
        return event.toReplyTarget(noteContext)
    }

    val candidate = event.toReplyTarget(noteContext)
    val rootHint = event.timelineRootHint() ?: return candidate
    val rootId = rootHint.id.takeIf(::isFullEventId) ?: return null
    val relayHint = rootHint.relayUrl
    val root = try {
        rootFetcher.fetch(rootId, relayHint)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    } ?: return candidate

    return ReplyTarget.Timeline(
        root = ReplyEventReference(
            id = root.id,
            kind = root.kind,
            pubkey = root.pubkey,
            relayUrl = relayHint,
            pubkeyRelayUrl = (candidate as? ReplyTarget.Timeline)?.root
                ?.takeIf { it.pubkey == root.pubkey }
                ?.pubkeyRelayUrl,
        ),
        parent = ReplyEventReference(id = event.id, kind = event.kind, pubkey = event.pubkey),
    )
}

private object DefaultReplyRootEventFetcher : ReplyRootEventFetcher {
    override suspend fun fetch(id: String, relayHint: String?): NostrEvent? = supervisorScope {
        val results = Channel<NostrEvent?>(capacity = 2)
        val jobs = buildList {
            add(launch { results.send(runCatchingFetch { fetchFromEnabledRelays(id) }) })
            relayHint?.let { hint ->
                add(launch { results.send(runCatchingFetch { fetchFromHintedRelay(id, hint) }) })
            }
        }
        repeat(jobs.size) {
            val event = results.receive()
            if (event != null) {
                jobs.forEach { it.cancel() }
                return@supervisorScope event
            }
        }
        null
    }

    private suspend fun fetchFromEnabledRelays(id: String): NostrEvent? {
        var resolved: NostrEvent? = null
        EventByIdFetcher(timeoutMillis = REPLY_ROOT_FETCH_TIMEOUT_MILLIS).fetch(setOf(id)) { resolved = it }
        return resolved
    }

    private suspend fun runCatchingFetch(block: suspend () -> NostrEvent?): NostrEvent? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private suspend fun fetchFromHintedRelay(id: String, relayUrl: String): NostrEvent? = coroutineScope {
        val subscriptionId = "reply-root-${Random.nextLong().toULong()}"
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(REPLY_ROOT_FETCH_TIMEOUT_MILLIS) {
                NostrRepository.events(subscriptionId).first { event ->
                    event.id == id && withContext(Dispatchers.Default) { isValidEvent(event) }
                }
            }
        }
        try {
            NostrRepository.subscribeTemporaryRelay(
                subscriptionId,
                NostrFilter(ids = listOf(id), limit = 1),
                relayUrl,
            )
            result.await()
        } finally {
            result.cancel()
            NostrRepository.closeTemporaryRelay(subscriptionId)
        }
    }
}
