package com.nostr.torinos.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import cnames.structs.__CFDictionary
import cnames.structs.__CFURL
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRetain
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSItemProvider
import platform.Foundation.NSURL
import platform.ImageIO.CGImageSourceCreateThumbnailAtIndex
import platform.ImageIO.CGImageSourceCreateWithURL
import platform.ImageIO.kCGImageSourceCreateThumbnailFromImageAlways
import platform.ImageIO.kCGImageSourceCreateThumbnailWithTransform
import platform.ImageIO.kCGImageSourceThumbnailMaxPixelSize
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerConfigurationAssetRepresentationModeCurrent
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UniformTypeIdentifiers.UTTypeImage
import platform.UIKit.UIApplication
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UISceneActivationStateForegroundActive
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.darwin.NSObject
import kotlin.coroutines.resume

// PHPickerViewController の delegate は weak 参照のため、選択完了まで保持する。
private val activePickerDelegates = mutableSetOf<PhotoPickerDelegate>()

@Composable
actual fun rememberImagePickerLauncher(
    onResult: (ByteArray?, String?) -> Unit,
): () -> Unit {
    val scope = rememberCoroutineScope()
    val currentOnResult = rememberUpdatedState(onResult)
    return remember(scope) {
        {
            scope.launch {
                val picked = pickOptimizedImage()
                currentOnResult.value(picked?.uploadBytes, picked?.mimeType)
            }
        }
    }
}

@Composable
actual fun rememberOptimizedImagePickerLauncher(
    onResult: (PickedImageData?) -> Unit,
): () -> Unit {
    val scope = rememberCoroutineScope()
    val currentOnResult = rememberUpdatedState(onResult)
    return remember(scope) {
        {
            scope.launch {
                currentOnResult.value(pickOptimizedImage())
            }
        }
    }
}

private suspend fun pickOptimizedImage(): PickedImageData? {
    val provider = pickImageProvider() ?: return null
    return provider.loadOptimizedImage()
}

private suspend fun pickImageProvider(): NSItemProvider? = suspendCancellableCoroutine { cont ->
    val configuration = PHPickerConfiguration().apply {
        filter = PHPickerFilter.imagesFilter
        selectionLimit = 1
        preferredAssetRepresentationMode = PHPickerConfigurationAssetRepresentationModeCurrent
    }
    val picker = PHPickerViewController(configuration)

    val delegate = PhotoPickerDelegate { provider ->
        if (cont.isActive) cont.resume(provider)
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
    val activeScene = UIApplication.sharedApplication.connectedScenes
        .filterIsInstance<UIWindowScene>()
        .firstOrNull { it.activationState == UISceneActivationStateForegroundActive }
    val window = activeScene?.windows
        ?.filterIsInstance<UIWindow>()
        ?.firstOrNull { it.isKeyWindow() }
        ?: activeScene?.windows?.filterIsInstance<UIWindow>()?.firstOrNull()
    var current = window?.rootViewController
    while (current?.presentedViewController != null) {
        current = current.presentedViewController
    }
    return current
}

private class PhotoPickerDelegate(
    private val onResult: (NSItemProvider?) -> Unit,
) : NSObject(), PHPickerViewControllerDelegateProtocol {

    override fun picker(
        picker: PHPickerViewController,
        didFinishPicking: List<*>,
    ) {
        val provider = (didFinishPicking.firstOrNull() as? PHPickerResult)?.itemProvider
        picker.dismissViewControllerAnimated(true) {
            activePickerDelegates -= this
            onResult(provider)
        }
    }
}

private suspend fun NSItemProvider.loadOptimizedImage(): PickedImageData? =
    suspendCancellableCoroutine { cont ->
        val progress = loadFileRepresentationForTypeIdentifier(UTTypeImage.identifier) { url, error ->
            val result = if (url != null && error == null) {
                runCatching { createOptimizedImageData(url) }.getOrNull()
            } else {
                null
            }
            if (cont.isActive) cont.resume(result)
        }
        cont.invokeOnCancellation { progress.cancel() }
    }

@OptIn(ExperimentalForeignApi::class)
private fun createOptimizedImageData(url: NSURL): PickedImageData? {
    val urlRef = CFBridgingRetain(url)?.reinterpret<__CFURL>() ?: return null
    val source = try {
        CGImageSourceCreateWithURL(urlRef, null)
    } finally {
        CFRelease(urlRef)
    } ?: return null

    return try {
        val uploadBytes = source.jpegThumbnailBytes(
            maxDimension = MAX_UPLOAD_DIMENSION,
            quality = UPLOAD_JPEG_QUALITY,
        ) ?: return null
        val previewBytes = source.jpegThumbnailBytes(
            maxDimension = PREVIEW_DIMENSION,
            quality = PREVIEW_JPEG_QUALITY,
        ) ?: uploadBytes
        PickedImageData(
            uploadBytes = uploadBytes,
            previewBytes = previewBytes,
            mimeType = "image/jpeg",
        )
    } finally {
        CFRelease(source)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun CPointer<cnames.structs.CGImageSource>.jpegThumbnailBytes(
    maxDimension: Int,
    quality: Double,
): ByteArray? {
    val alwaysKey = bridgedString(kCGImageSourceCreateThumbnailFromImageAlways) ?: return null
    val transformKey = bridgedString(kCGImageSourceCreateThumbnailWithTransform) ?: return null
    val maxSizeKey = bridgedString(kCGImageSourceThumbnailMaxPixelSize) ?: return null
    val options = mapOf<Any?, Any>(
        alwaysKey to true,
        transformKey to true,
        maxSizeKey to maxDimension,
    )
    val optionsRef = CFBridgingRetain(options)?.reinterpret<__CFDictionary>() ?: return null
    val thumbnail = try {
        CGImageSourceCreateThumbnailAtIndex(this, 0u, optionsRef)
    } finally {
        CFRelease(optionsRef)
    } ?: return null

    return try {
        val image = UIImage.imageWithCGImage(thumbnail)
        UIImageJPEGRepresentation(image, quality)?.toByteArray()
    } finally {
        CFRelease(thumbnail)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun bridgedString(value: CPointer<out CPointed>?): String? {
    val retained = CFRetain(value) ?: return null
    return CFBridgingRelease(retained) as? String
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

private const val MAX_UPLOAD_DIMENSION = 2_048
private const val PREVIEW_DIMENSION = 256
private const val UPLOAD_JPEG_QUALITY = 0.85
private const val PREVIEW_JPEG_QUALITY = 0.8
