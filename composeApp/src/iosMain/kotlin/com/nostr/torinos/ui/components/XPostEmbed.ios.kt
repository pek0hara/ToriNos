package com.nostr.torinos.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.interop.UIKitView
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.nostr.torinos.util.cacheTraceLog
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
    deferLoad: Boolean,
) {
    var contentHeight by remember(postId, darkTheme) { mutableStateOf(MinimumXPostHeight) }
    var contentWidthPx by remember(postId, darkTheme) { mutableStateOf(0) }
    var assetsReady by remember(postId, darkTheme) { mutableStateOf(false) }
    val cacheKey = remember(postId, darkTheme, contentWidthPx) {
        XPostSnapshotCacheKey(postId, darkTheme, contentWidthPx)
    }
    var snapshot by remember(cacheKey) {
        mutableStateOf(if (contentWidthPx > 0) XPostSnapshotCache[cacheKey] else null)
    }
    val webViewRef = remember(postId, darkTheme) { XPostWebViewRef() }
    val embedUrl = remember(postId, darkTheme) { xPostEmbedUrl(postId, darkTheme) }
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(cacheKey, assetsReady, contentHeight) {
        if (!assetsReady || snapshot != null || contentWidthPx <= 0) return@LaunchedEffect
        // Compose側の高さ変更がWKWebViewのframeへ反映されるのを待ってから撮影する。
        delay(SnapshotLayoutDelayMillis)
        val webView = webViewRef.value ?: return@LaunchedEffect
        webView.layoutIfNeeded()
        cacheTraceLog { "[XPostEmbed] capturing snapshot postId=${cacheKey.postId} widthPx=${cacheKey.widthPx}" }
        val capturedImage = webView.captureSnapshot()
        if (capturedImage != null) {
            XPostSnapshotCache[cacheKey] = capturedImage
            snapshot = capturedImage
        } else {
            cacheTraceLog { "[XPostEmbed] captureSnapshot returned null postId=${cacheKey.postId}" }
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
                .onSizeChanged { size ->
                    // 回転や分割画面などで表示幅が変わった場合、古い幅で撮ったスナップショットを使い回さない。
                    if (contentWidthPx != 0 && contentWidthPx != size.width) {
                        snapshot = null
                        assetsReady = false
                        contentHeight = MinimumXPostHeight
                    }
                    contentWidthPx = size.width
                }
                .clickable { uriHandler.openUri(sourceUrl) },
        )
        return
    }

    Box(modifier = modifier.fillMaxWidth()) {
        if (deferLoad) {
            // スクロール中はWKWebViewを新規生成しない。予約領域だけ確保し、停止後に実体を生成する。
            // ここでも幅を測ってcontentWidthPxを更新しないと、遅延中はキャッシュキーの幅が
            // 常に0のままになり、スナップショットキャッシュがヒットしていても素通りしてしまう。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { size -> contentWidthPx = size.width }
                    .height(contentHeight),
            )
        } else {
            UIKitView(
                factory = {
                    // deferLoadでの破棄・再生成後もassetsReadyが古いtrueのままだと、
                    // 新しいWKWebViewが未読み込みなのにスピナーが消え、スナップショットも
                    // 未完成のまま撮影されてしまう。生成のたびに必ずfalseへ戻す。
                    assetsReady = false
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
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { size ->
                        if (contentWidthPx != 0 && contentWidthPx != size.width) {
                            contentHeight = MinimumXPostHeight
                            assetsReady = false
                            // JS側のreadySignalledラッチは1ページ読み込みにつき1回きりなので、
                            // 幅変更後に再度readyシグナルを受け取れるよう明示的に読み直す。
                            webViewRef.value?.reload()
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

        if (deferLoad || !assetsReady) {
            // deferLoad中(未生成)・WKWebView読み込み中のどちらも「読み込み中」とわかるようにする。
            // deferLoadも条件に含めるのは、直前のスナップショット撮影失敗などでassetsReadyが
            // trueのまま古い値を引きずっているケースでも、遅延中は確実にスピナーを出すため
            // (スナップショットが取れている場合はここへ来る前にreturn済み)。
            Box(
                modifier = Modifier.fillMaxWidth().height(contentHeight),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
    }
}

private class XPostWebViewRef {
    var value: WKWebView? = null
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
