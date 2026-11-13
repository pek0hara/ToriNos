package com.nostr.torinos.network

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path

internal actual fun platformCacheTextStore(fileName: String): LocalTextStore? =
    FileTextStore(appCacheDirectory() / fileName)

/** アプリ専用のキャッシュディレクトリ(Androidは`cacheDir`、iOSは`Caches`)。 */
internal expect fun appCacheDirectory(): Path

/** ファイルの読み書きを行うディスパッチャー。中継リレーの処理を止めないよう、AndroidではIOを使う。 */
internal expect val cacheFileDispatcher: CoroutineDispatcher

/** 一時ファイルへ書いてから置き換え、書き込み途中で終了しても壊れたファイルを残さない。 */
internal class FileTextStore(
    private val path: Path,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val dispatcher: CoroutineDispatcher = cacheFileDispatcher,
) : LocalTextStore {
    override suspend fun read(): String? = withContext(dispatcher) {
        if (!fileSystem.exists(path)) return@withContext null
        fileSystem.read(path) { readUtf8() }
    }

    override suspend fun write(content: String) = withContext(dispatcher) {
        val directory = checkNotNull(path.parent)
        fileSystem.createDirectories(directory)
        val temporary = directory / "${path.name}.tmp"
        fileSystem.write(temporary) { writeUtf8(content) }
        fileSystem.atomicMove(temporary, path)
    }
}
