package com.nostr.torinos.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UniformTypeIdentifiers.UTTypeItem
import platform.darwin.NSObject
import kotlin.coroutines.resume

// UIDocumentPickerViewController の delegate は weak 参照のため、選択完了まで保持する。
private val activeDocumentPickerDelegates = mutableSetOf<DocumentPickerDelegate>()

@Composable
actual fun rememberTextFilePickerLauncher(
    onResult: (String?) -> Unit,
): () -> Unit {
    val scope = rememberCoroutineScope()
    val currentOnResult = rememberUpdatedState(onResult)
    return remember(scope) {
        {
            scope.launch {
                val url = pickDocumentUrl()
                currentOnResult.value(url?.let(::readSmallTextFile))
            }
        }
    }
}

private suspend fun pickDocumentUrl(): NSURL? = suspendCancellableCoroutine { cont ->
    // asCopy = true でアプリ内の一時コピーを受け取るので、セキュリティスコープの解除は要らない。
    val picker = UIDocumentPickerViewController(
        forOpeningContentTypes = listOf(UTTypeItem),
        asCopy = true,
    )
    picker.allowsMultipleSelection = false
    val delegate = DocumentPickerDelegate { url ->
        if (cont.isActive) cont.resume(url)
    }
    activeDocumentPickerDelegates += delegate
    picker.delegate = delegate

    val rootVC = topViewController() ?: run {
        activeDocumentPickerDelegates -= delegate
        cont.resume(null)
        return@suspendCancellableCoroutine
    }
    rootVC.presentViewController(picker, animated = true, completion = null)
}

@OptIn(ExperimentalForeignApi::class)
private fun readSmallTextFile(url: NSURL): String? {
    val data: NSData = NSData.dataWithContentsOfURL(url) ?: return null
    if (data.length.toLong() > MAX_TEXT_FILE_BYTES) return null
    val bytes = data.bytes?.readBytes(data.length.toInt()) ?: ByteArray(0)
    return bytes.decodeToString()
}

private class DocumentPickerDelegate(
    private val onFinish: (NSURL?) -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>,
    ) {
        finish(didPickDocumentsAtURLs.firstOrNull() as? NSURL)
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        finish(null)
    }

    private fun finish(url: NSURL?) {
        activeDocumentPickerDelegates -= this
        onFinish(url)
    }
}
