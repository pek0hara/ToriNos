package com.nostr.torinos.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class ChannelContentTest {
    @Test
    fun contentContainsCompleteMetadataIncludingNormalizedRelays() {
        val content = ChannelMeta(
            name = "n",
            about = "a",
            picture = "https://example.com/p.png",
            relays = listOf("wss://Yabu.me/", "https://bad", "wss://yabu.me"),
        ).toChannelContent()
        val json = Json.parseToJsonElement(content).jsonObject
        assertEquals("n", json.getValue("name").jsonPrimitive.content)
        assertEquals("https://example.com/p.png", json.getValue("picture").jsonPrimitive.content)
        assertEquals(listOf("wss://yabu.me"), json.getValue("relays").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun editingNameKeepsRelaysAndPictureAndRoundTripsThroughParser() {
        // kind 41 編集は実効メタデータを copy して作る。relays が落ちると推奨リレーが消える(L4-R1)。
        val effective = ChannelMeta("old", "about", "https://example.com/p.png", listOf("wss://a", "wss://b"))
        val edited = effective.copy(name = "new")
        val event = NostrEvent("u", "owner", 1, 41, emptyList(), edited.toChannelContent(), "sig")
        assertEquals(edited, event.toChannelMeta())
    }

    @Test
    fun emptyRelaysAreWrittenExplicitly() {
        val json = Json.parseToJsonElement(ChannelMeta(name = "n").toChannelContent()).jsonObject
        assertEquals(0, json.getValue("relays").jsonArray.size)
        assertEquals("", json.getValue("picture").jsonPrimitive.content)
    }
}
