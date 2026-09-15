package com.nostr.torinos.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.interop.UIKitView
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.skia.Image as SkiaImage
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSData
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.UIKit.UIColor
import platform.UIKit.UIImagePNGRepresentation
import platform.UIKit.UIScreen
import platform.UIKit.UIScrollViewContentInsetAdjustmentBehavior
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKSnapshotConfiguration
import platform.WebKit.WKUserContentController
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject
import platform.posix.memcpy
import kotlin.coroutines.resume

@OptIn(ExperimentalForeignApi::class)
@Suppress("DEPRECATION")
@Composable
internal actual fun XPostEmbed(
    postId: String,
    sourceUrl: String,
    darkTheme: Boolean,
    modifier: Modifier,
) {
    var contentHeight by remember(postId, darkTheme) { mutableStateOf(MinimumXPostHeight) }
    var contentWidthPx by remember(postId, darkTheme) { mutableStateOf(0) }
    var assetsReady by remember(postId, darkTheme) { mutableStateOf(false) }
    val cacheKey = remember(postId, darkTheme) { XPostSnapshotCacheKey(postId, darkTheme) }
    var snapshot by remember(cacheKey) {
        mutableStateOf(XPostSnapshotCache[cacheKey])
    }
    val webViewRef = remember(postId, darkTheme) { XPostWebViewRef() }
    val embedUrl = remember(postId, darkTheme) { xPostEmbedUrl(postId, darkTheme) }
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(assetsReady, contentHeight) {
        if (!assetsReady || snapshot != null) return@LaunchedEffect
        // Compose側の高さ変更がWKWebViewのframeへ反映されるのを待ってから撮影する。
        delay(SnapshotLayoutDelayMillis)
        val webView = webViewRef.value ?: return@LaunchedEffect
        webView.layoutIfNeeded()
        webView.captureSnapshot()?.let { capturedImage ->
            XPostSnapshotCache[cacheKey] = capturedImage
            snapshot = capturedImage
        }
    }

    snapshot?.let { image ->
        Image(
            bitmap = image,
            contentDescription = "Xの投稿",
            contentScale = ContentScale.FillWidth,
            modifier = modifier
                .fillMaxWidth()
                .aspectRatio(image.width.toFloat() / image.height.toFloat())
                .clickable { uriHandler.openUri(sourceUrl) },
        )
        return
    }

    UIKitView(
        factory = {
            val contentController = WKUserContentController()
            contentController.addUserScript(
                WKUserScript(
                    source = IosSnapshotScript,
                    injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentEnd,
                    forMainFrameOnly = true,
                ),
            )
            contentController.addScriptMessageHandler(
                XPostHeightHandler { height ->
                    height
                        .takeIf { it in MinimumReportedHeight..MaximumReportedHeight }
                        ?.dp
                        ?.let { contentHeight = it }
                },
                name = HeightBridgeName,
            )
            contentController.addScriptMessageHandler(
                XPostReadyHandler { assetsReady = true },
                name = ReadyBridgeName,
            )

            val configuration = WKWebViewConfiguration().apply {
                userContentController = contentController
            }
            WKWebView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0), configuration = configuration).apply {
                opaque = false
                backgroundColor = UIColor.clearColor
                scrollView.scrollEnabled = false
                scrollView.bounces = false
                scrollView.contentInsetAdjustmentBehavior =
                    UIScrollViewContentInsetAdjustmentBehavior.UIScrollViewContentInsetAdjustmentNever
                scrollView.automaticallyAdjustsScrollIndicatorInsets = false
                webViewRef.value = this
            }
        },
        modifier = modifier
            .onSizeChanged { size ->
                if (contentWidthPx != 0 && contentWidthPx != size.width) {
                    contentHeight = MinimumXPostHeight
                    assetsReady = false
                }
                contentWidthPx = size.width
            }
            .height(contentHeight),
        update = { webView ->
            if (webView.URL?.absoluteString != embedUrl) {
                NSURL.URLWithString(embedUrl)?.let { url ->
                    webView.loadRequest(NSURLRequest.requestWithURL(url))
                }
            }
        },
        onRelease = { webView ->
            if (webViewRef.value === webView) webViewRef.value = null
            webView.stopLoading()
            webView.configuration.userContentController.apply {
                removeScriptMessageHandlerForName(HeightBridgeName)
                removeScriptMessageHandlerForName(ReadyBridgeName)
            }
        },
    )
}

private class XPostWebViewRef {
    var value: WKWebView? = null
}

private data class XPostSnapshotCacheKey(
    val postId: String,
    val darkTheme: Boolean,
)

/**
 * タイムライン項目が破棄・再生成されても、同じ投稿のWebViewを読み直さずに済むようにする。
 * 画像が際限なく残らないよう、直近の投稿だけをLRU方式で保持する。
 */
private object XPostSnapshotCache {
    private const val MaximumEntries = 8
    private val entries = LinkedHashMap<XPostSnapshotCacheKey, ImageBitmap>()

    operator fun get(key: XPostSnapshotCacheKey): ImageBitmap? =
        entries.remove(key)?.also { image -> entries[key] = image }

    operator fun set(key: XPostSnapshotCacheKey, image: ImageBitmap) {
        entries.remove(key)
        entries[key] = image
        while (entries.size > MaximumEntries) {
            entries.remove(entries.keys.first())
        }
    }
}

private class XPostHeightHandler(
    private val onHeight: (Double) -> Unit,
) : NSObject(), WKScriptMessageHandlerProtocol {
    override fun userContentController(
        userContentController: WKUserContentController,
        didReceiveScriptMessage: WKScriptMessage,
    ) {
        val height = (didReceiveScriptMessage.body as? NSNumber)?.doubleValue ?: return
        onHeight(height)
    }
}

private class XPostReadyHandler(
    private val onReady: () -> Unit,
) : NSObject(), WKScriptMessageHandlerProtocol {
    override fun userContentController(
        userContentController: WKUserContentController,
        didReceiveScriptMessage: WKScriptMessage,
    ) {
        onReady()
    }
}

@OptIn(ExperimentalForeignApi::class)
private suspend fun WKWebView.captureSnapshot(): ImageBitmap? =
    suspendCancellableCoroutine { continuation ->
        val displayScale = UIScreen.mainScreen.scale
        val viewWidth = bounds.useContents { size.width }
        val snapshotConfiguration = WKSnapshotConfiguration().apply {
            // 3x端末でも表示上の2px/ptを上限にし、キャッシュ画像のメモリを抑える。
            snapshotWidth = NSNumber(
                double = viewWidth * minOf(1.0, XPostSnapshotPixelsPerPoint / displayScale),
            )
        }
        takeSnapshotWithConfiguration(snapshotConfiguration) { image, _ ->
            val bitmap = image
                ?.let(::UIImagePNGRepresentation)
                ?.toByteArray()
                ?.let { bytes -> runCatching { SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull() }
            if (continuation.isActive) continuation.resume(bitmap)
        }
    }

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val result = ByteArray(length.toInt())
    result.usePinned { pinned ->
        memcpy(pinned.addressOf(0), bytes, length.convert())
    }
    return result
}

private const val HeightBridgeName = "torinosXPost"
private const val ReadyBridgeName = "torinosXPostReady"
private val MinimumXPostHeight = 250.dp
private const val MinimumReportedHeight = 100.0
private const val MaximumReportedHeight = 4_000.0
private const val SnapshotLayoutDelayMillis = 100L
private const val XPostSnapshotPixelsPerPoint = 2.0

private val IosSnapshotScript = """
    (function() {
      if (window.__torinosSnapshotBridgeInstalled) return;
      window.__torinosSnapshotBridgeInstalled = true;
      var viewport = document.querySelector('meta[name="viewport"]');
      if (!viewport) {
        viewport = document.createElement('meta');
        viewport.name = 'viewport';
        document.head.appendChild(viewport);
      }
      viewport.content = 'width=device-width, initial-scale=1, maximum-scale=1';
      var lastHeight = 0;
      var readyTimer = 0;
      var readySignalled = false;

      function reportHeight() {
        var body = document.body;
        var app = document.getElementById('app');
        var height = Math.ceil(Math.max(
          app ? app.scrollHeight : 0,
          app ? app.offsetHeight : 0,
          body ? body.scrollHeight : 0,
          body ? body.offsetHeight : 0
        ));
        if (height > 0 && height !== lastHeight) {
          lastHeight = height;
          window.webkit.messageHandlers.torinosXPost.postMessage(height);
        }
      }

      function signalReady() {
        if (readySignalled) return;
        readySignalled = true;
        reportHeight();
        window.webkit.messageHandlers.torinosXPostReady.postMessage(true);
      }

      function waitForAssets() {
        clearTimeout(readyTimer);
        readyTimer = setTimeout(function() {
          var imagePromises = Array.from(document.images).map(function(image) {
            if (image.complete) return Promise.resolve();
            return new Promise(function(resolve) {
              image.addEventListener('load', resolve, { once: true });
              image.addEventListener('error', resolve, { once: true });
            });
          });
          var fontsReady = document.fonts ? document.fonts.ready : Promise.resolve();
          Promise.all([fontsReady].concat(imagePromises)).then(function() {
            requestAnimationFrame(function() {
              requestAnimationFrame(signalReady);
            });
          });
        }, 300);
      }

      var observed = document.getElementById('app') || document.body;
      if (window.ResizeObserver) {
        new ResizeObserver(function() {
          reportHeight();
          waitForAssets();
        }).observe(observed);
      }
      if (window.MutationObserver) {
        new MutationObserver(function() {
          reportHeight();
          waitForAssets();
        }).observe(observed, { childList: true, subtree: true, attributes: true });
      }
      window.addEventListener('load', waitForAssets);
      reportHeight();
      waitForAssets();
      setTimeout(signalReady, 4000);
    })();
""".trimIndent()
