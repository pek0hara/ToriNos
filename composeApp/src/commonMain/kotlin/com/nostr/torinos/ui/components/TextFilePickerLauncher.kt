package com.nostr.torinos.ui.components

import androidx.compose.runtime.Composable

/** 読み込むテキストファイルの上限。秘密鍵ファイルなどの小さなファイルだけを想定する。 */
internal const val MAX_TEXT_FILE_BYTES = 64 * 1024

/**
 * ファイルを 1 つ選ばせて、中身を UTF-8 の文字列で返すランチャー。
 * 拡張子が独自(.nsec など)でも選べるよう、種類は絞らない。
 * キャンセル・読み込み失敗・大きすぎるファイルのときは null を返す。
 */
@Composable
expect fun rememberTextFilePickerLauncher(
    onResult: (String?) -> Unit,
): () -> Unit
