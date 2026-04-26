package com.nostr.torinos.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

private val json = Json { ignoreUnknownKeys = true }

sealed class RelayMessage {
    data class Event(val subscriptionId: String, val event: NostrEvent) : RelayMessage()
    data class EndOfStoredEvents(val subscriptionId: String) : RelayMessage()
    data class Closed(val subscriptionId: String, val message: String) : RelayMessage()
    data class Notice(val message: String) : RelayMessage()
    data class Unknown(val raw: String) : RelayMessage()
}

/** ["EVENT", <event>] メッセージを構築 */
fun buildEventMessage(event: NostrEvent): String {
    val array = buildJsonArray {
        add(kotlinx.serialization.json.JsonPrimitive("EVENT"))
        add(json.encodeToJsonElement(event))
    }
    return json.encodeToString(kotlinx.serialization.json.JsonArray.serializer(), array)
}

fun parseRelayMessage(raw: String): RelayMessage = try {
    val array = json.parseToJsonElement(raw).jsonArray
    when (array[0].jsonPrimitive.content) {
        "EVENT" -> RelayMessage.Event(
            subscriptionId = array[1].jsonPrimitive.content,
            event = json.decodeFromJsonElement(array[2]),
        )
        "EOSE" -> RelayMessage.EndOfStoredEvents(array[1].jsonPrimitive.content)
        "CLOSED" -> RelayMessage.Closed(
            subscriptionId = array[1].jsonPrimitive.content,
            message = array.getOrNull(2)?.jsonPrimitive?.content ?: "",
        )
        "NOTICE" -> RelayMessage.Notice(array[1].jsonPrimitive.content)
        else -> RelayMessage.Unknown(raw)
    }
} catch (_: Exception) {
    RelayMessage.Unknown(raw)
}
