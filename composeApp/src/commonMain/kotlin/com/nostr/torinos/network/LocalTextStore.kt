package com.nostr.torinos.network

/** キャッシュの保存先。1つの文字列をまとめて読み書きする。 */
internal interface LocalTextStore {
    suspend fun read(): String?
    suspend fun write(content: String)
}

/** アプリ専用のキャッシュ領域にある[fileName]。永続化しないプラットフォーム(Web)ではnull。 */
internal expect fun platformCacheTextStore(fileName: String): LocalTextStore?
