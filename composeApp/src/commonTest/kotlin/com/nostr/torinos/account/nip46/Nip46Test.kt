package com.nostr.torinos.account.nip46

import com.nostr.torinos.account.AccountSessionLease
import com.nostr.torinos.account.SignerAttention
import com.nostr.torinos.account.SignerCredential
import com.nostr.torinos.account.SignerException
import com.nostr.torinos.crypto.Nip44
import com.nostr.torinos.crypto.derivePublicKey
import com.nostr.torinos.crypto.fromHex
import com.nostr.torinos.crypto.isValidEvent
import com.nostr.torinos.crypto.signEvent
import com.nostr.torinos.crypto.toHex
import com.nostr.torinos.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

class Nip46Test {
    private val signerSecret = "0000000000000000000000000000000000000000000000000000000000000005"
    private val signerPubkey = derivePublicKey(signerSecret.fromHex()).toHex()
    private val userKey = "0000000000000000000000000000000000000000000000000000000000000003"
    private val userPubkey = "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9"
    private val clientSecret = "0000000000000000000000000000000000000000000000000000000000000007"

    /** 実際の鍵で復号・署名して応答する偽の署名器。 */
    private inner class FakeRemote(
        var respond: (method: String, params: List<String>) -> List<Pair<String?, String?>> = ::defaultResponse,
    ) {
        val requests = mutableListOf<Pair<String, List<String>>>()
        var lastId = ""
        var lastClient = ""
        private val channel = Channel<NostrEvent>(Channel.UNLIMITED)

        fun send(toClient: String, json: String, from: String = signerSecret) {
            channel.trySend(
                signEvent(privateKeyHex = from, content = Nip44.encrypt(json, from, toClient), kind = NIP46_KIND,
                    tags = listOf(listOf("p", toClient))),
            )
        }

        val transport = object : Nip46Transport {
            override val incoming: Flow<NostrEvent> = channel.receiveAsFlow()
            override fun start(scope: CoroutineScope) = Unit
            override suspend fun publish(event: NostrEvent) {
                assertTrue(isValidEvent(event))
                assertEquals(listOf(listOf("p", signerPubkey)), event.tags)
                val (id, method, params) = decodeNip46Request(Nip44.decrypt(event.content, signerSecret, event.pubkey))!!
                requests += method to params
                lastId = id
                lastClient = event.pubkey
                respond(method, params).forEach { (result, error) ->
                    send(event.pubkey, buildJsonObject {
                        put("id", id)
                        result?.let { put("result", it) }
                        error?.let { put("error", it) }
                    }.toString())
                }
            }
            override fun close() = Unit
        }
    }

    private fun defaultResponse(method: String, params: List<String>): List<Pair<String?, String?>> = listOf(
        when (method) {
            "connect" -> "ack" to null
            "get_public_key" -> userPubkey to null
            "ping" -> "pong" to null
            "nip44_encrypt" -> Nip44.encrypt(params[1], userKey, params[0]) to null
            "nip44_decrypt" -> Nip44.decrypt(params[1], userKey, params[0]) to null
            "sign_event" -> {
                val obj = Json.parseToJsonElement(params[0]).jsonObject
                val signed = signEvent(
                    privateKeyHex = userKey,
                    content = obj["content"]!!.jsonPrimitive.content,
                    kind = obj["kind"]!!.jsonPrimitive.int,
                    tags = obj["tags"]!!.jsonArray.map { tag -> tag.jsonArray.map { it.jsonPrimitive.content } },
                    createdAt = obj["created_at"]!!.jsonPrimitive.long,
                )
                Json.encodeToString(NostrEvent.serializer(), signed) to null
            }
            else -> null to "unknown method"
        },
    )

    private fun signer(remote: FakeRemote, scope: CoroutineScope, lease: AccountSessionLease = AccountSessionLease()) =
        Nip46AccountSigner(
            pubkey = userPubkey,
            credential = SignerCredential.Nip46(clientSecret, signerPubkey, listOf("wss://relay.example")),
            lease = lease,
            transportFactory = { _, _ -> remote.transport },
            scope = scope,
        )

    @Test
    fun bunkerUriIsParsedAndValidated() {
        val uri = parseBunkerUri("bunker://$signerPubkey?relay=wss%3A%2F%2Frelay.example&relay=wss://two.example&secret=abc").getOrThrow()
        assertEquals(signerPubkey, uri.remoteSignerPubkey)
        assertEquals(2, uri.relays.size)
        assertEquals("abc", uri.secret)
        assertTrue(parseBunkerUri("bunker://xyz?relay=wss://relay.example").isFailure)
        assertTrue(parseBunkerUri("bunker://$signerPubkey").isFailure)
        assertTrue(parseBunkerUri("nostrconnect://$signerPubkey?relay=wss://relay.example").isFailure)
    }

    @Test
    fun nostrConnectUriCarriesRelaysSecretAndPermissions() {
        val uri = buildNostrConnectUri(signerPubkey, listOf("wss://relay.example"), "s3cret", NIP46_PERMISSIONS)
        assertTrue(uri.startsWith("nostrconnect://$signerPubkey?relay=wss%3A%2F%2Frelay.example&secret=s3cret&perms="))
        assertTrue("sign_event%3A1%2C" in uri)
        assertTrue(uri.endsWith("&name=ToriNos"))
    }

    @Test
    fun permissionsCoverEveryKindToriNosSigns() {
        listOf(0, 1, 3, 5, 6, 7, 1111, 10000, 10002, 22242, 27235, 30023, 31234).forEach { kind ->
            assertTrue("sign_event:$kind," in "$NIP46_PERMISSIONS,", "kind $kind")
        }
        assertTrue("nip44_encrypt" in NIP46_PERMISSIONS && "nip44_decrypt" in NIP46_PERMISSIONS)
    }

    @Test
    fun bunkerLoginSendsTheSecretAndReturnsTheUserPubkey() = runTest {
        val remote = FakeRemote()
        val connected = Nip46Login.connectBunker(
            BunkerUri(signerPubkey, listOf("wss://relay.example"), "abc"),
            transportFactory = { _, _ -> remote.transport },
            scope = backgroundScope,
        )
        assertEquals(userPubkey, connected.pubkey)
        assertEquals(signerPubkey, connected.credential.remoteSignerPubkey)
        assertEquals(listOf(signerPubkey, "abc", NIP46_PERMISSIONS), remote.requests.first().second)
        assertEquals(listOf("connect", "get_public_key"), remote.requests.map { it.first })
    }

    @Test
    fun signerSignsThroughTheRemoteAndVerifiesTheResult() = runTest {
        val remote = FakeRemote()
        val signer = signer(remote, backgroundScope)
        val event = signer.sign("こんにちは", kind = 1, tags = listOf(listOf("t", "nostr")), createdAt = 100)
        assertEquals(userPubkey, event.pubkey)
        assertEquals("こんにちは", event.content)
        assertTrue(isValidEvent(event))
        assertEquals("secret", signer.decrypt(signer.encryptToSelf("secret"), userPubkey))
    }

    @Test
    fun signerRejectsAnEventSignedWithDifferentContent() = runTest {
        val remote = FakeRemote()
        remote.respond = { method, params ->
            if (method == "sign_event") {
                val changed = signEvent(privateKeyHex = userKey, content = "改ざん", kind = 1, tags = emptyList(), createdAt = 100)
                listOf(Json.encodeToString(NostrEvent.serializer(), changed) to null)
            } else defaultResponse(method, params)
        }
        assertFailsWith<SignerException.InvalidResponse> { signer(remote, backgroundScope).sign("本文", createdAt = 100) }
    }

    @Test
    fun authUrlIsShownUntilTheSignerApproves() = runTest {
        val url = "https://signer.example/approve"
        val remote = FakeRemote(respond = { _, _ -> listOf("auth_url" to url) })
        val signer = signer(remote, backgroundScope)
        val result = async { signer.encryptToSelf("text") }
        runCurrent()
        // 承認を待っている間は帯で知らせ、要求は続けて待つ。
        assertEquals(SignerAttention.ApprovalRequired(url), signer.attention.value)
        val ciphertext = Nip44.encrypt("text", userKey, userPubkey)
        remote.send(remote.lastClient, """{"id":"${remote.lastId}","result":"$ciphertext"}""")
        assertEquals(ciphertext, result.await())
        assertNull(signer.attention.value)
    }

    @Test
    fun noResponseTimesOutAndMarksTheSignerDisconnected() = runTest {
        val remote = FakeRemote(respond = { _, _ -> emptyList() })
        val signer = signer(remote, backgroundScope)
        assertFailsWith<SignerException.Timeout> { signer.sign("本文") }
        assertIs<SignerAttention.Disconnected>(signer.attention.value)
        // 自動で再送しない。
        assertEquals(1, remote.requests.size)
    }

    @Test
    fun rejectionFromTheSignerIsReported() = runTest {
        val remote = FakeRemote(respond = { _, _ -> listOf(null to "user rejected the request") })
        assertFailsWith<SignerException.Rejected> { signer(remote, backgroundScope).sign("本文") }
    }

    @Test
    fun responsesFromAnotherPubkeyAreIgnored() = runTest {
        val remote = FakeRemote(respond = { _, _ -> emptyList() })
        val signer = signer(remote, backgroundScope)
        launch {
            // 別の鍵からの応答は、ID が合っていても受け取らない。
            remote.send(derivePublicKey(clientSecret.fromHex()).toHex(), """{"id":"x","result":"pong"}""", from = userKey)
        }
        assertFailsWith<SignerException.Timeout> { signer.sign("本文") }
    }

    @Test
    fun sessionEndStopsRequests() = runTest {
        val lease = AccountSessionLease()
        val signer = signer(FakeRemote(), backgroundScope, lease)
        lease.invalidate()
        assertFailsWith<IllegalStateException> { signer.sign("本文") }
    }

    @Test
    fun nostrConnectWaitsForTheSignerToReturnTheSecret() = runTest {
        val remote = FakeRemote()
        var clientPubkey = ""
        val pending = Nip46Login.startNostrConnect(
            relays = listOf("wss://relay.example"),
            transportFactory = { _, client -> clientPubkey = client; remote.transport },
            scope = backgroundScope,
        )
        val secret = pending.uri.substringAfter("secret=").substringBefore('&')
        launch {
            remote.send(clientPubkey, """{"id":"c1","result":"wrong"}""")
            remote.send(clientPubkey, """{"id":"c2","result":"$secret"}""")
        }
        val connected = pending.await()
        assertEquals(signerPubkey, connected.credential.remoteSignerPubkey)
        assertEquals(userPubkey, connected.pubkey)
    }
}
