package com.nostr.torinos.network

import kotlin.test.Test
import kotlin.test.assertEquals

class ImageUploaderTest {
    @Test
    fun preservesNip94MetadataFromUploadResponse() {
        val response = """
            {
              "nip94_event": {
                "tags": [
                  ["url", "https://media.example/image.jpg"],
                  ["m", "image/jpeg"],
                  ["x", "saved-hash"],
                  ["ox", "original-hash"],
                  ["dim", "800x600"],
                  ["thumb", "https://media.example/thumb.jpg"]
                ]
              }
            }
        """.trimIndent()

        val media = ImageUploader.parseMediaMetadata(response)

        assertEquals("https://media.example/image.jpg", media?.url)
        assertEquals("saved-hash", media?.sha256)
        assertEquals("original-hash", media?.originalSha256)
        assertEquals("https://media.example/thumb.jpg", media?.thumbnailUrl)
    }

    @Test
    fun keepsLegacyUploadResponseCompatible() {
        val media = ImageUploader.parseMediaMetadata(
            body = """{"data":[{"url":"https://media.example/legacy.jpg"}]}""",
            fallbackMimeType = "image/jpeg",
        )

        assertEquals("https://media.example/legacy.jpg", media?.url)
        assertEquals("image/jpeg", media?.mimeType)
    }
}
