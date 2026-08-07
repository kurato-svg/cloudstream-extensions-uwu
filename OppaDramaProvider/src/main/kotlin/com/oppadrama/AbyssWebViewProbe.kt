package com.oppadrama

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Collections
import kotlin.coroutines.resume

object AbyssWebViewProbe {

    private const val MAX_WAIT_MS = 14000L

    suspend fun probe(
        url: String,
        referer: String
    ): String = withContext(Dispatchers.Main) {
        val context = OppaRuntime.context
            ?: return@withContext debugText(
                url = url,
                stage = "Context missing",
                checks = listOf(
                    Pair(false, "Plugin context available")
                ),
                requests = emptyList(),
                jsResult = "OppaRuntime.context is null"
            )

        suspendCancellableCoroutine { continuation ->
            val requests = Collections.synchronizedList(mutableListOf<String>())
            val handler = Handler(Looper.getMainLooper())
            val webView = WebView(context)

            fun addRequest(requestUrl: String?) {
                val value = requestUrl?.trim().orEmpty()
                if (value.isBlank()) return

                val lower = value.lowercase()
                val wanted = lower.contains("abyss") ||
                    lower.contains("iamcdn") ||
                    lower.contains("jwplayer") ||
                    lower.contains("sssrr") ||
                    lower.contains("sora") ||
                    lower.contains(".m3u8") ||
                    lower.contains(".mp4") ||
                    lower.contains("playlist") ||
                    lower.contains("stream") ||
                    lower.contains("media") ||
                    lower.contains("blob:")

                if (wanted && !requests.contains(value)) {
                    requests.add(value)
                }
            }

            fun clickWebView() {
                runCatching {
                    val now = SystemClock.uptimeMillis()
                    val x = 540f
                    val y = 540f

                    webView.dispatchTouchEvent(
                        MotionEvent.obtain(
                            now,
                            now,
                            MotionEvent.ACTION_DOWN,
                            x,
                            y,
                            0
                        )
                    )

                    webView.dispatchTouchEvent(
                        MotionEvent.obtain(
                            now,
                            now + 80,
                            MotionEvent.ACTION_UP,
                            x,
                            y,
                            0
                        )
                    )
                }
            }

            fun finish(stage: String, jsResult: String) {
                runCatching {
                    handler.removeCallbacksAndMessages(null)
                    webView.stopLoading()
                    webView.loadUrl("about:blank")
                    webView.removeAllViews()
                    webView.destroy()
                }

                if (continuation.isActive) {
                    val snapshot = synchronized(requests) { requests.toList() }

                    val checks: List<Pair<Boolean, String>> = listOf(
                        Pair(true, "Plugin context available"),
                        Pair(
                            snapshot.any { it.contains("abyssplayer.com/?v=", true) },
                            "Original Abyss iframe requested"
                        ),
                        Pair(
                            !snapshot.any { it.equals("https://abyss.to/", true) },
                            "Not forced to abyss.to landing page"
                        ),
                        Pair(
                            snapshot.any { it.contains("play.abyssplayer.com", true) },
                            "play.abyssplayer.com requested"
                        ),
                        Pair(
                            snapshot.any { it.contains("iamcdn", true) },
                            "iamcdn assets loaded"
                        ),
                        Pair(
                            snapshot.any { it.contains("jwplayer", true) },
                            "JWPlayer assets requested"
                        ),
                        Pair(
                            jsResult.contains("iframeCount", true),
                            "Wrapper probe executed"
                        ),
                        Pair(
                            jsResult.contains(".m3u8", true) ||
                                snapshot.any { it.contains(".m3u8", true) },
                            "m3u8 seen"
                        ),
                        Pair(
                            jsResult.contains(".mp4", true) ||
                                snapshot.any { it.contains(".mp4", true) },
                            "mp4 seen"
                        ),
                        Pair(
                            snapshot.any {
                                it.contains("sora", true) ||
                                    it.contains("sssrr", true)
                            },
                            "Abyss segment/domain seen"
                        )
                    )

                    continuation.resume(
                        debugText(
                            url = url,
                            stage = stage,
                            checks = checks,
                            requests = snapshot,
                            jsResult = jsResult
                        )
                    )
                }
            }

            continuation.invokeOnCancellation {
                runCatching {
                    handler.removeCallbacksAndMessages(null)
                    webView.destroy()
                }
            }

            @SuppressLint("SetJavaScriptEnabled")
            fun setup() {
                WebView.setWebContentsDebuggingEnabled(true)

                webView.layout(0, 0, 1080, 1080)

                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.mediaPlaybackRequiresUserGesture = false
                webView.settings.loadsImagesAutomatically = true
                webView.settings.javaScriptCanOpenWindowsAutomatically = true
                webView.settings.setSupportMultipleWindows(false)
                webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                webView.settings.userAgentString =
                    "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 Chrome/139.0 Mobile Safari/537.36"

                webView.webChromeClient = WebChromeClient()

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(
                        view: WebView?,
                        requestUrl: String?,
                        favicon: Bitmap?
                    ) {
                        addRequest(requestUrl)
                    }

                    override fun onPageFinished(
                        view: WebView?,
                        requestUrl: String?
                    ) {
                        addRequest(requestUrl)
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): android.webkit.WebResourceResponse? {
                        addRequest(request?.url?.toString())
                        return super.shouldInterceptRequest(view, request)
                    }

                    @Deprecated("Deprecated in Android")
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        requestUrl: String?
                    ): Boolean {
                        addRequest(requestUrl)
                        return shouldBlockNavigation(requestUrl)
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val requestUrl = request?.url?.toString()
                        addRequest(requestUrl)
                        return shouldBlockNavigation(requestUrl)
                    }
                }

                /*
                 * Abyss has this protection:
                 * if(top.location == self.location && hostname is not *.abyss.to) window.location = "https://abyss.to"
                 *
                 * Direct WebView loading triggers that redirect.
                 * The real OppaDrama website loads Abyss inside an iframe.
                 * This wrapper copies that behaviour.
                 */
                val escapedUrl = url
                    .replace("&", "&amp;")
                    .replace("\"", "&quot;")

                val wrapper = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <meta name="viewport" content="width=device-width, initial-scale=1.0">
                        <style>
                            html, body, iframe {
                                margin: 0;
                                padding: 0;
                                width: 100%;
                                height: 100%;
                                background: #000;
                                border: 0;
                                overflow: hidden;
                            }
                        </style>
                    </head>
                    <body>
                        <iframe
                            id="oppa_abyss_frame"
                            src="$escapedUrl"
                            allow="autoplay; fullscreen; encrypted-media; picture-in-picture"
                            allowfullscreen>
                        </iframe>
                    </body>
                    </html>
                """.trimIndent()

                addRequest(url)

                webView.loadDataWithBaseURL(
                    referer,
                    wrapper,
                    "text/html",
                    "UTF-8",
                    null
                )

                handler.postDelayed({ clickWebView() }, 3500L)
                handler.postDelayed({ clickWebView() }, 6500L)
                handler.postDelayed({ clickWebView() }, 9500L)

                handler.postDelayed({
                    runCatching {
                        webView.evaluateJavascript(PROBE_JS) { result ->
                            finish(
                                stage = "Iframe wrapper probe completed",
                                jsResult = result ?: "null"
                            )
                        }
                    }.onFailure {
                        finish(
                            stage = "Iframe wrapper probe failed",
                            jsResult = it.message ?: it.toString()
                        )
                    }
                }, MAX_WAIT_MS)
            }

            runCatching {
                setup()
            }.onFailure {
                finish(
                    stage = "WebView setup failed",
                    jsResult = it.message ?: it.toString()
                )
            }
        }
    }

    private fun shouldBlockNavigation(requestUrl: String?): Boolean {
        val value = requestUrl?.lowercase().orEmpty()

        if (value.isBlank()) return false

        val allowed = value.startsWith("about:") ||
            value.startsWith("data:") ||
            value.contains("45.11.57.192") ||
            value.contains("oppa.biz") ||
            value.contains("abyss") ||
            value.contains("iamcdn") ||
            value.contains("jwplayer") ||
            value.contains("sssrr") ||
            value.contains("sora") ||
            value.contains(".m3u8") ||
            value.contains(".mp4")

        return !allowed
    }

    private fun debugText(
        url: String,
        stage: String,
        checks: List<Pair<Boolean, String>>,
        requests: List<String>,
        jsResult: String
    ): String {
        val checkText = checks.joinToString("\n") { pair ->
            "[${if (pair.first) "✓" else "✗"}] ${pair.second}"
        }

        val requestText = requests
            .take(25)
            .joinToString("\n") { it.take(180) }
            .ifBlank { "No matched request captured" }

        val cleanJs = jsResult
            .replace("\\n", " ")
            .replace("\\\"", "\"")
            .take(2200)

        return """
========== OPPA IFRAME DEBUG ==========

Hydrax URL:
$url

Stage:
$stage

Checks:
$checkText

Captured Requests:
$requestText

Top Frame JS Result:
$cleanJs

=======================================
        """.trimIndent()
    }

    private const val PROBE_JS = """
(function() {
  function safe(name, fn) {
    try {
      return fn();
    } catch (e) {
      return "ERR " + name + ": " + e.message;
    }
  }

  var result = {};
  result.href = location.href;
  result.title = document.title;
  result.readyState = document.readyState;
  result.iframeCount = document.querySelectorAll("iframe").length;
  result.iframeSrcs = Array.prototype.slice.call(document.querySelectorAll("iframe")).map(function(f) {
    return f.src || "";
  });
  result.topHasJw = typeof window.jwplayer;
  result.topHasSoTrym = typeof window.SoTrym;
  result.bodyText = (document.body ? document.body.innerText : "").slice(0, 600);
  result.bodyHtmlHints = safe("htmlHints", function() {
    var html = document.documentElement.innerHTML;
    var urls = html.match(/https?:\/\/[^"'<>\\s]+/g) || [];
    return urls.filter(function(u) {
      return /m3u8|mp4|sora|sssrr|abyss|iamcdn|jwplayer/i.test(u);
    }).slice(0, 40);
  });

  return JSON.stringify(result);
})()
    """
}
