package com.nostr.torinos.ui.components

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nostr.torinos.util.cacheTraceLog
import kotlinx.coroutines.delay

@SuppressLint("SetJavaScriptEnabled")
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
    val density = LocalDensity.current

    LaunchedEffect(cacheKey, assetsReady, contentHeight) {
        if (!assetsReady || snapshot != null || contentWidthPx <= 0) return@LaunchedEffect
        val webView = webViewRef.value ?: return@LaunchedEffect
        val targetHeightPx = with(density) { contentHeight.roundToPx() }
        // Compose側の高さ変更がWebViewの実レイアウトへ反映されるのを、最大試行回数まで確認してから撮影する。
        repeat(MaxSnapshotLayoutAttempts) { attempt ->
            delay(SnapshotLayoutDelayMillis)
            // deferLoadによる破棄でonReleaseがwebViewRefをnull化・別インスタンスへ差し替えている
            // 場合、破棄済み/別のWebViewをこの後触るとクラッシュしうるため中断する。
            if (webViewRef.value !== webView) return@LaunchedEffect
            val isLastAttempt = attempt == MaxSnapshotLayoutAttempts - 1
            if (webView.height >= targetHeightPx || isLastAttempt) {
                cacheTraceLog {
                    "[XPostEmbed] capturing snapshot postId=${cacheKey.postId} widthPx=${cacheKey.widthPx} " +
                        "attempt=$attempt isLastAttempt=$isLastAttempt"
                }
                val capturedImage = webView.captureSnapshotBitmap()
                if (capturedImage != null) {
                    XPostSnapshotCache[cacheKey] = capturedImage
                    snapshot = capturedImage
                } else {
                    cacheTraceLog { "[XPostEmbed] captureSnapshotBitmap returned null postId=${cacheKey.postId}" }
                }
                return@LaunchedEffect
            }
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
            // スクロール中はWebViewを新規生成しない。予約領域だけ確保し、停止後に実体を生成する。
            // ここでも幅を測ってcontentWidthPxを更新しないと、遅延中はキャッシュキーの幅が
            // 常に0のままになり、スナップショットキャッシュがヒットしていても素通りしてしまう。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { size -> contentWidthPx = size.width }
                    .height(contentHeight),
            )
        } else {
            AndroidView(
                factory = { context ->
                    // deferLoadでの破棄・再生成後もassetsReadyが古いtrueのままだと、
                    // 新しいWebViewが未読み込みなのにスピナーが消え、スナップショットも
                    // 未完成のまま撮影されてしまう。生成のたびに必ずfalseへ戻す。
                    assetsReady = false
                    WebView(context).apply {
                        setBackgroundColor(Color.TRANSPARENT)
                        overScrollMode = View.OVER_SCROLL_NEVER
                        isVerticalScrollBarEnabled = false
                        isHorizontalScrollBarEnabled = false
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mediaPlaybackRequiresUserGesture = true
                        webViewRef.value = this

                        addJavascriptInterface(
                            XPostHeightBridge { height ->
                                post {
                                    height
                                        .takeIf { it in MinimumReportedHeight..MaximumReportedHeight }
                                        ?.dp
                                        ?.takeIf { it > contentHeight }
                                        ?.let { contentHeight = it }
                                }
                            },
                            HeightBridgeName,
                        )
                        addJavascriptInterface(
                            XPostReadyBridge {
                                post { assetsReady = true }
                            },
                            ReadyBridgeName,
                        )
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) {
                                super.onPageFinished(view, url)
                                if (url?.startsWith(XEmbedOrigin) == true) {
                                    view.evaluateJavascript(AndroidResizeScript, null)
                                }
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean {
                                if (!request.isForMainFrame || request.url.toString() == embedUrl) return false
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, request.url))
                                }
                                return true
                            }
                        }
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
                    if (webView.url != embedUrl) webView.loadUrl(embedUrl)
                },
                onRelease = { webView ->
                    if (webViewRef.value === webView) webViewRef.value = null
                    webView.stopLoading()
                    webView.removeJavascriptInterface(HeightBridgeName)
                    webView.removeJavascriptInterface(ReadyBridgeName)
                    webView.destroy()
                },
            )
        }

        if (deferLoad || !assetsReady) {
            // deferLoad中(未生成)・WebView読み込み中のどちらも「読み込み中」とわかるようにする。
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
    var value: WebView? = null
}

private class XPostHeightBridge(
    private val onHeight: (Float) -> Unit,
) {
    @JavascriptInterface
    fun report(height: Float) {
        onHeight(height)
    }
}

private class XPostReadyBridge(
    private val onReady: () -> Unit,
) {
    @JavascriptInterface
    fun ready() {
        onReady()
    }
}

/** WebViewの表示内容をビットマップ化する。ハードウェアレイヤーのままだと draw(Canvas) が空白になることがあるため撮影時のみソフトウェアレイヤーへ切り替える。 */
private fun WebView.captureSnapshotBitmap(): ImageBitmap? {
    val viewWidth = width
    val viewHeight = height
    if (viewWidth <= 0 || viewHeight <= 0) return null

    val density = resources.displayMetrics.density
    val scale = minOf(1f, XPostSnapshotMaxDensity / density)
    val bitmapWidth = (viewWidth * scale).toInt().coerceAtLeast(1)
    val bitmapHeight = (viewHeight * scale).toInt().coerceAtLeast(1)
    val previousLayerType = layerType

    return runCatching {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        draw(canvas)
        bitmap.asImageBitmap()
    }.getOrNull().also {
        setLayerType(previousLayerType, null)
    }
}

private const val HeightBridgeName = "ToriNosXPost"
private const val ReadyBridgeName = "ToriNosXPostReady"
private const val XEmbedOrigin = "https://platform.twitter.com/embed/Tweet.html"
private val MinimumXPostHeight = 250.dp
private const val MinimumReportedHeight = 100f
private const val MaximumReportedHeight = 4_000f
private const val SnapshotLayoutDelayMillis = 100L
private const val MaxSnapshotLayoutAttempts = 3
// 高密度端末でもキャッシュ画像のメモリを抑えるため、撮影解像度を2x相当に制限する。
private const val XPostSnapshotMaxDensity = 2f

private val AndroidResizeScript = """
    (function() {
      if (window.__torinosResizeObserverInstalled) return;
      window.__torinosResizeObserverInstalled = true;
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
          window.ToriNosXPost.report(height);
        }
      }

      function signalReady() {
        if (readySignalled) return;
        readySignalled = true;
        reportHeight();
        window.ToriNosXPostReady.ready();
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
        }).observe(observed, {
          childList: true, subtree: true, attributes: true
        });
      }
      window.addEventListener('load', waitForAssets);
      reportHeight();
      waitForAssets();
      setTimeout(signalReady, 4000);
    })();
""".trimIndent()
