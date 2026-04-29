package com.nostr.torinos.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.UIKit.UIApplication
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImagePickerController
import platform.UIKit.UIImagePickerControllerDelegateProtocol
import platform.UIKit.UIImagePickerControllerOriginalImage
import platform.UIKit.UIImagePickerControllerSourceType
import platform.UIKit.UINavigationControllerDelegateProtocol
import platform.UIKit.UIViewController
import platform.darwin.NSObject
import kotlin.coroutines.resume

// UIImagePickerController の delegate は weak 参照のため GC されないよう保持する
private val activePickerDelegates = mutableSetOf<ImagePickerDelegate>()

@Composable
actual fun rememberImagePickerLauncher(
    onResult: (ByteArray?, String?) -> Unit,
): () -> Unit {
    val scope = rememberCoroutineScope()
    return remember {
        {
            scope.launch {
                val bytes = pickImageBytes()
                onResult(bytes, if (bytes != null) "image/jpeg" else null)
            }
        }
    }
}

private suspend fun pickImageBytes(): ByteArray? = suspendCancellableCoroutine { cont ->
    val picker = UIImagePickerController()
    picker.sourceType = UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypePhotoLibrary
    picker.allowsEditing = false

    val delegate = ImagePickerDelegate { bytes ->
        if (cont.isActive) cont.resume(bytes)
    }
    activePickerDelegates += delegate
    picker.delegate = delegate

    val rootVC = topViewController() ?: run {
        activePickerDelegates -= delegate
        cont.resume(null)
        return@suspendCancellableCoroutine
    }
    rootVC.presentViewController(picker, animated = true, completion = null)

    cont.invokeOnCancellation {
        activePickerDelegates -= delegate
        picker.dismissViewControllerAnimated(false, null)
    }
}

private fun topViewController(): UIViewController? {
    @Suppress("DEPRECATION")
    var current = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (current?.presentedViewController != null) {
        current = current.presentedViewController
    }
    return current
}

private class ImagePickerDelegate(
    private val onResult: (ByteArray?) -> Unit,
) : NSObject(), UIImagePickerControllerDelegateProtocol, UINavigationControllerDelegateProtocol {

    override fun imagePickerController(
        picker: UIImagePickerController,
        didFinishPickingMediaWithInfo: Map<Any?, *>,
    ) {
        picker.dismissViewControllerAnimated(true) {
            activePickerDelegates -= this
        }
        val image = didFinishPickingMediaWithInfo[UIImagePickerControllerOriginalImage] as? UIImage
        if (image == null) {
            onResult(null)
            return
        }
        val jpegData = UIImageJPEGRepresentation(image, 0.85)
        onResult(jpegData?.toByteArray())
    }

    override fun imagePickerControllerDidCancel(picker: UIImagePickerController) {
        picker.dismissViewControllerAnimated(true) {
            activePickerDelegates -= this
        }
        onResult(null)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val length = this.length.toInt()
    if (length == 0) return ByteArray(0)
    return ByteArray(length).also { bytes ->
        bytes.usePinned { pinned ->
            platform.posix.memcpy(pinned.addressOf(0), this.bytes, this.length)
        }
    }
}
