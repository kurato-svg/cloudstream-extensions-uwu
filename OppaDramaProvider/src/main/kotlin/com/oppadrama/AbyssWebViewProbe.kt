package com.oppadrama

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
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

    private const val MAX_WAIT_MS = 12000L
    private const val JS_DELAY_MS = 6000L

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
                    lower.contains("media")

                if (wanted && !requests.contains(value)) {
                    requests.add(value)
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
                            snapshot.any { it.contains("abyss", true) },
                            "Abyss request seen"
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
                            jsResult.contains("\"hasJw\":\"function\"") ||
                                jsResult.contains("\\\"hasJw\\\":\\\"function\\\""),
                            "window.jwplayer exists"
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

                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.mediaPlaybackRequiresUserGesture = false
                webView.settings.loadsImagesAutomatically = true
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

                        handler.postDelayed({
                            runCatching {
                                view?.evaluateJavascript(PROBE_JS) { result ->
                                    finish(
                                        stage = "JavaScript probe completed",
                                        jsResult = result ?: "null"
                                    )
                                }
                            }.onFailure {
                                finish(
                                    stage = "JavaScript probe failed",
                                    jsResult = it.message ?: it.toString()
                                )
                            }
                        }, JS_DELAY_MS)
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
                        return false
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        addRequest(request?.url?.toString())
                        return false
                    }
                }

                val headers = mapOf(
                    "Referer" to referer,
                    "Origin" to Uri.parse(referer).let {
                        "${it.scheme}://${it.host}"
                    },
                    "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8"
                )

                webView.loadUrl(url, headers)

                handler.postDelayed({
                    finish(
                        stage = "Timeout after ${MAX_WAIT_MS / 1000}s",
                        jsResult = "Probe timeout"
                    )
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
            .take(18)
            .joinToString("\n") { it.take(180) }
            .ifBlank { "No matched request captured" }

        val cleanJs = jsResult
            .replace("\\n", " ")
            .replace("\\\"", "\"")
            .take(1800)

        return """
========== OPPA WEBVIEW DEBUG ==========

Hydrax URL:
$url

Stage:
$stage

Checks:
$checkText

Captured Requests:
$requestText

JS Probe Result:
$cleanJs

========================================
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

  try {
    var overlay = document.getElementById("overlay");
    if (overlay) overlay.click();
  } catch(e) {}

  var result = {};
  result.href = location.href;
  result.title = document.title;
  result.readyState = document.readyState;
  result.hasJw = typeof window.jwplayer;
  result.hasSoTrym = typeof window.SoTrym;
  result.hasDatas = typeof window.datas;

  result.jwState = safe("jwState", function() {
    if (typeof window.jwplayer === "undefined") return null;
    return window.jwplayer().getState ? window.jwplayer().getState() : null;
  });

  result.playlist = safe("playlist", function() {
    if (typeof window.jwplayer === "undefined") return null;
    return window.jwplayer().getPlaylist ? window.jwplayer().getPlaylist() : null;
  });

  result.playlistItem = safe("playlistItem", function() {
    if (typeof window.jwplayer === "undefined") return null;
    return window.jwplayer().getPlaylistItem ? window.jwplayer().getPlaylistItem() : null;
  });

  result.config = safe("config", function() {
    if (typeof window.jwplayer === "undefined") return null;
    return window.jwplayer().getConfig ? window.jwplayer().getConfig() : null;
  });

  result.videos = Array.prototype.slice.call(document.querySelectorAll("video")).map(function(v) {
    return {
      src: v.src || "",
      currentSrc: v.currentSrc || "",
      readyState: v.readyState,
      networkState: v.networkState
    };
  });

  result.sources = Array.prototype.slice.call(document.querySelectorAll("source")).map(function(s) {
    return s.src || "";
  });

  result.htmlHints = document.documentElement.innerHTML.match(/https?:\/\/[^"'<>\\s]+/g);
  if (result.htmlHints) {
    result.htmlHints = result.htmlHints.filter(function(u) {
      return /m3u8|mp4|sora|sssrr|abyss|iamcdn|jwplayer/i.test(u);
    }).slice(0, 30);
  }

  return JSON.stringify(result);
})()
    """
}
