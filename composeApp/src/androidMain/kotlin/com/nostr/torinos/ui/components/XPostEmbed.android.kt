package com.nostr.torinos.ui.components

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal actual fun XPostEmbed(
    postId: String,
    sourceUrl: String,
    darkTheme: Boolean,
    modifier: Modifier,
) {
    var contentHeight by remember(postId) { mutableStateOf(MinimumXPostHeight) }
    var contentWidthPx by remember(postId) { mutableStateOf(0) }
    val embedUrl = remember(postId, darkTheme) { xPostEmbedUrl(postId, darkTheme) }

    AndroidView(
        factory = { context ->
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
        modifier = modifier
            .onSizeChanged { size ->
                if (contentWidthPx != 0 && contentWidthPx != size.width) {
                    contentHeight = MinimumXPostHeight
                }
                contentWidthPx = size.width
            }
            .height(contentHeight),
        update = { webView ->
            if (webView.url != embedUrl) webView.loadUrl(embedUrl)
        },
        onRelease = { webView ->
            webView.stopLoading()
            webView.removeJavascriptInterface(HeightBridgeName)
            webView.destroy()
        },
    )
}

private class XPostHeightBridge(
    private val onHeight: (Float) -> Unit,
) {
    @JavascriptInterface
    fun report(height: Float) {
        onHeight(height)
    }
}

private const val HeightBridgeName = "ToriNosXPost"
private const val XEmbedOrigin = "https://platform.twitter.com/embed/Tweet.html"
private val MinimumXPostHeight = 250.dp
private const val MinimumReportedHeight = 100f
private const val MaximumReportedHeight = 4_000f

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
      if (window.ResizeObserver) {
        new ResizeObserver(reportHeight).observe(document.getElementById('app') || document.body);
      }
      if (window.MutationObserver) {
        new MutationObserver(reportHeight).observe(document.getElementById('app') || document.body, {
          childList: true, subtree: true, attributes: true
        });
      }
      window.addEventListener('load', reportHeight);
      requestAnimationFrame(function() {
        requestAnimationFrame(reportHeight);
      });
      setTimeout(reportHeight, 500);
      setTimeout(reportHeight, 1500);
    })();
""".trimIndent()
