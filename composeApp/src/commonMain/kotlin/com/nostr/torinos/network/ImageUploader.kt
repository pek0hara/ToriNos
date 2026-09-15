package com.nostr.torinos.network

import com.nostr.torinos.account.AccountSigner
import com.nostr.torinos.model.MediaMetadata
import com.nostr.torinos.model.parseNip94Entries
import com.nostr.torinos.util.networkTraceLog
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val UPLOAD_URL = "https://nostr.build/api/v2/upload/files"
private val json = Json { ignoreUnknownKeys = true }

object ImageUploader {

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun upload(bytes: ByteArray, mimeType: String, signer: AccountSigner?): Result<String> =
        uploadMedia(bytes, mimeType, signer).map { it.url }

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun uploadMedia(
        bytes: ByteArray,
        mimeType: String,
        signer: AccountSigner?,
    ): Result<MediaMetadata> = runCatching {
        val activeSigner = signer ?: error("秘密鍵が設定されていません")
        // NIP-98: kind:27235 イベントを署名して Authorization ヘッダーに付与
        val authEvent = activeSigner.sign(
            content = "",
            kind = 27235,
            tags = listOf(
                listOf("u", UPLOAD_URL),
                listOf("method", "POST"),
            ),
        )
        val authToken = Base64.encode(Json.encodeToString(authEvent).encodeToByteArray())

        val ext = mimeType.substringAfter('/').substringBefore(';').ifBlank { "jpg" }
        networkTraceLog { "[ImageUploader] bytes=${bytes.size} mimeType=$mimeType" }
        val response = NostrRepository.httpClient.post(UPLOAD_URL) {
            header(HttpHeaders.Authorization, "Nostr $authToken")
            setBody(
                MultiPartFormDataContent(
                    formData {
                        // Ktor は key から "form-data; name=\"file\"" を自動生成し、
                        // headers の Content-Disposition 値と joinToString("; ") で結合する。
                        // そのため filename= のみ追加すれば OK。
                        append(
                            key = "file",
                            value = bytes,
                            headers = Headers.build {
                                append(HttpHeaders.ContentType, mimeType)
                                append(HttpHeaders.ContentDisposition, "filename=\"upload.$ext\"")
                            },
                        )
                    }
                )
            )
        }

        val body = response.bodyAsText()
        networkTraceLog { "[ImageUploader] status=${response.status} body=$body" }

        if (response.status.value !in 200..299) {
            error("サーバーエラー ${response.status.value}: $body")
        }

        parseMediaMetadata(body, mimeType) ?: error("URLが見つかりませんでした: $body")
    }

    internal fun parseMediaMetadata(body: String, fallbackMimeType: String? = null): MediaMetadata? {
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            return null
        }

        // NIP-96: nip94_event.tags に ["url", "..."]
        val nip94Entries = (root["nip94_event"] as? JsonObject)
            ?.let { it["tags"] as? JsonArray }
            ?.mapNotNull { tag ->
                val arr = tag as? JsonArray ?: return@mapNotNull null
                val key = (arr.getOrNull(0) as? JsonPrimitive)?.content ?: return@mapNotNull null
                val value = (arr.getOrNull(1) as? JsonPrimitive)?.content ?: return@mapNotNull null
                "$key $value"
            }
        nip94Entries?.let(::parseNip94Entries)?.let { metadata ->
            return if (metadata.mimeType == null && fallbackMimeType != null) {
                metadata.copy(mimeType = fallbackMimeType.lowercase())
            } else {
                metadata
            }
        }

        // data[0].url
        ((root["data"] as? JsonArray)?.firstOrNull() as? JsonObject)
            ?.let { it["url"] as? JsonPrimitive }
            ?.content?.takeIf { it.isNotBlank() }
            ?.let { return MediaMetadata(url = it, mimeType = fallbackMimeType?.lowercase()) }

        // トップレベル url
        (root["url"] as? JsonPrimitive)?.content
            ?.takeIf { it.isNotBlank() }
            ?.let { return MediaMetadata(url = it, mimeType = fallbackMimeType?.lowercase()) }

        return null
    }
}
