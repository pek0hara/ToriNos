package com.nostr.torinos.ui.components

import androidx.compose.runtime.Composable

/** 読み込むテキストファイルの上限。秘密鍵ファイルなどの小さなファイルだけを想定する。 */
internal const val MAX_TEXT_FILE_BYTES = 64 * 1024

/** ファイルを選んだ結果。キャンセルは null で表す。 */
sealed interface TextFilePickResult {
    data class Picked(val text: String) : TextFilePickResult

    /** [MAX_TEXT_FILE_BYTES] を超えている。中身は読まない。 */
    data object TooLarge : TextFilePickResult

    data object Unreadable : TextFilePickResult
}

/** ファイルの大きさで判定してから、上限以内なら [decode] で読む。 */
internal inline fun textFilePickResult(byteSize: Long, decode: () -> String): TextFilePickResult {
    if (byteSize > MAX_TEXT_FILE_BYTES) return TextFilePickResult.TooLarge
    return runCatching(decode).fold(
        onSuccess = { TextFilePickResult.Picked(it) },
        onFailure = { TextFilePickResult.Unreadable },
    )
}

/**
 * ファイルを 1 つ選ばせて、中身を UTF-8 の文字列で返すランチャー。
 * 拡張子が独自(.nsec など)でも選べるよう、種類は絞らない。
 * キャンセルのときは null を返す。大きすぎる・読めないときは、その理由を返す。
 */
@Composable
expect fun rememberTextFilePickerLauncher(
    onResult: (TextFilePickResult?) -> Unit,
): () -> Unit
