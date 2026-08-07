package com.oppadrama

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import kotlin.coroutines.resume

object AbyssWebViewProbe {

    private const val MAX_WAIT_MS = 22000L

    private data class CapturedRequest(
        val url: String,
        val method: String,
        val headers: Map<String, String>,
        val cookie: String,
        var status: Int? = null,
        var responseHeaders: Map<String, String> = emptyMap(),
        var error: String? = null
    )

    suspend fun debug(
        url: String,
        referer: String
    ): String = withContext(Dispatchers.Main) {
        val context = OppaRuntime.context
            ?: return@withContext "OPPA HYDRAX DEBUG FAILED: OppaRuntime.context is null"

        suspendCancellableCoroutine { continuation ->
            val requests = Collections.synchronizedList(mutableListOf<CapturedRequest>())
            val events = Collections.synchronizedList(mutableListOf<String>())
            val handler = Handler(Looper.getMainLooper())
            val webView = WebView(context)

            fun addEvent(value: String?) {
                val clean = value?.trim().orEmpty()
                if (clean.isBlank()) return

                if (!events.contains(clean)) {
                    events.add(clean)
                }
            }

            fun rememberRequest(
                requestUrl: String?,
                method: String = "GET",
                headers: Map<String, String> = emptyMap()
            ) {
                val cleanUrl = requestUrl?.trim().orEmpty()
                if (cleanUrl.isBlank()) return

                val lower = cleanUrl.lowercase()
                val wanted = lower.contains("abyss") ||
                    lower.contains("iamcdn") ||
                    lower.contains("jwplayer") ||
                    lower.contains("sssrr") ||
                    lower.contains("/sora/") ||
                    lower.contains("m3u8") ||
                    lower.contains("mp4") ||
                    lower.contains("blob:") ||
                    lower.contains("api") ||
                    lower.contains("source") ||
                    lower.contains("stream")

                if (!wanted) return

                synchronized(requests) {
                    if (requests.none { it.url == cleanUrl }) {
                        val cookie = runCatching {
                            CookieManager.getInstance().getCookie(cleanUrl)
                        }.getOrNull().orEmpty()

                        requests.add(
                            CapturedRequest(
                                url = cleanUrl,
                                method = method,
                                headers = headers,
                                cookie = cookie
                            )
                        )
                    }
                }
            }

            fun clickWebView() {
                runCatching {
                    val now = SystemClock.uptimeMillis()
                    val x = 540f
                    val y = 540f

                    webView.dispatchTouchEvent(
                        MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
                    )

                    webView.dispatchTouchEvent(
                        MotionEvent.obtain(now, now + 80, MotionEvent.ACTION_UP, x, y, 0)
                    )
                }
            }

            fun finish(jsResult: String?) {
                runCatching {
                    handler.removeCallbacksAndMessages(null)
                    webView.stopLoading()
                    webView.loadUrl("about:blank")
                    webView.removeAllViews()
                    webView.destroy()
                }

                if (continuation.isActive) {
                    val requestSnapshot = synchronized(requests) { requests.toList() }
                    val eventSnapshot = synchronized(events) { events.toList() }

                    continuation.resume(
                        buildDebugReport(
                            hydraxUrl = url,
                            referer = referer,
                            requests = requestSnapshot,
                            events = eventSnapshot,
                            jsResult = jsResult.orEmpty()
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

                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(webView, true)

                webView.layout(0, 0, 1080, 1080)

                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.mediaPlaybackRequiresUserGesture = false
                webView.settings.loadsImagesAutomatically = true
                webView.settings.javaScriptCanOpenWindowsAutomatically = true
                webView.settings.setSupportMultipleWindows(false)
                webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                webView.settings.userAgentString = USER_AGENT

                webView.webChromeClient = WebChromeClient()

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(
                        view: WebView?,
                        requestUrl: String?,
                        favicon: Bitmap?
                    ) {
                        rememberRequest(requestUrl)
                    }

                    override fun onPageFinished(
                        view: WebView?,
                        requestUrl: String?
                    ) {
                        rememberRequest(requestUrl)
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val requestUrl = request?.url?.toString()
                        val method = request?.method ?: "GET"
                        val headers = request?.requestHeaders.orEmpty()

                        rememberRequest(requestUrl, method, headers)

                        if (
                            requestUrl?.contains("abyssplayer.com/?v=", true) == true
                        ) {
                            return runCatching {
                                injectIntoAbyssPage(
                                    pageUrl = requestUrl,
                                    referer = referer
                                )
                            }.onFailure {
                                addEvent("OPPA_INJECT_ERROR = ${it.message ?: it}")
                            }.getOrNull()
                        }

                        /*
                         * For stream requests, proxy the request so we can see the real
                         * response status and response headers used while WebView plays.
                         */
                        if (isStreamUrl(requestUrl)) {
                            return runCatching {
                                proxyAndLogStreamRequest(
                                    requestUrl = requestUrl!!,
                                    method = method,
                                    headers = headers,
                                    requests = requests
                                )
                            }.onFailure {
                                addEvent("OPPA_PROXY_ERROR = $requestUrl | ${it.message ?: it}")
                            }.getOrNull()
                        }

                        return super.shouldInterceptRequest(view, request)
                    }

                    @Deprecated("Deprecated in Android")
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        requestUrl: String?
                    ): Boolean {
                        rememberRequest(requestUrl)
                        return false
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        rememberRequest(
                            request?.url?.toString(),
                            request?.method ?: "GET",
                            request?.requestHeaders.orEmpty()
                        )
                        return false
                    }
                }

                val escapedUrl = htmlEscape(url)

                val wrapper = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <meta name="viewport" content="width=device-width, initial-scale=1.0">
                        <script>
                            window.__oppaEvents = [];
                            window.addEventListener("message", function(event) {
                                try {
                                    var data = event.data;
                                    if (typeof data === "string" && data.indexOf("OPPA_") >= 0) {
                                        window.__oppaEvents.push(data);
                                    }
                                } catch(e) {}
                            });
                        </script>
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

                webView.loadDataWithBaseURL(
                    referer,
                    wrapper,
                    "text/html",
                    "UTF-8",
                    null
                )

                handler.postDelayed({ clickWebView() }, 2500L)
                handler.postDelayed({ clickWebView() }, 5000L)
                handler.postDelayed({ clickWebView() }, 7500L)
                handler.postDelayed({ clickWebView() }, 10000L)

                handler.postDelayed({
                    runCatching {
                        webView.evaluateJavascript(PROBE_JS) { result ->
                            finish(result)
                        }
                    }.onFailure {
                        finish(null)
                    }
                }, MAX_WAIT_MS)
            }

            runCatching {
                setup()
            }.onFailure {
                finish("SETUP_ERROR: ${it.message ?: it}")
            }
        }
    }

    private fun injectIntoAbyssPage(
        pageUrl: String,
        referer: String
    ): WebResourceResponse {
        val connection = URL(pageUrl).openConnection() as HttpURLConnection

        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Referer", referer)
        connection.setRequestProperty(
            "Accept",
            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        )
        connection.setRequestProperty("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8")
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.connectTimeout = 10000
        connection.readTimeout = 10000

        val html = connection.inputStream.bufferedReader().use { it.readText() }

        connection.headerFields
            .filterKeys { it?.equals("Set-Cookie", true) == true }
            .values
            .flatten()
            .forEach { cookie ->
                runCatching {
                    CookieManager.getInstance().setCookie(pageUrl, cookie)
                }
            }

        runCatching {
            CookieManager.getInstance().flush()
        }

        val injected = when {
            html.contains("<head>", true) ->
                html.replaceFirst(
                    Regex("<head>", RegexOption.IGNORE_CASE),
                    "<head>$HOOK_JS"
                )

            else -> "$HOOK_JS$html"
        }

        return WebResourceResponse(
            "text/html",
            "UTF-8",
            ByteArrayInputStream(injected.toByteArray(Charsets.UTF_8))
        ).apply {
            responseHeaders = mapOf(
                "Access-Control-Allow-Origin" to "*"
            )
        }
    }

    private fun proxyAndLogStreamRequest(
        requestUrl: String,
        method: String,
        headers: Map<String, String>,
        requests: MutableList<CapturedRequest>
    ): WebResourceResponse {
        val connection = URL(requestUrl).openConnection() as HttpURLConnection

        connection.requestMethod = method
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 12000
        connection.readTimeout = 12000

        headers.forEach { entry ->
            val key = entry.key
            val value = entry.value

            if (
                key.equals("Host", true) ||
                key.equals("Connection", true) ||
                key.equals("Accept-Encoding", true)
            ) return@forEach

            connection.setRequestProperty(key, value)
        }

        if (headers.keys.none { it.equals("User-Agent", true) }) {
            connection.setRequestProperty("User-Agent", USER_AGENT)
        }

        if (headers.keys.none { it.equals("Accept", true) }) {
            connection.setRequestProperty("Accept", "*/*")
        }

        val cookie = runCatching {
            CookieManager.getInstance().getCookie(requestUrl)
        }.getOrNull().orEmpty()

        if (cookie.isNotBlank()) {
            connection.setRequestProperty("Cookie", cookie)
        }

        val status = connection.responseCode
        val responseHeaders = connection.headerFields
            .filterKeys { it != null }
            .mapKeys { it.key ?: "" }
            .mapValues { it.value.joinToString("; ") }

        synchronized(requests) {
            requests.find { it.url == requestUrl }?.apply {
                this.status = status
                this.responseHeaders = responseHeaders
                this.error = null
            }
        }

        val mimeType = responseHeaders["Content-Type"]
            ?.substringBefore(";")
            ?.ifBlank { "video/mp4" }
            ?: "video/mp4"

        val stream = if (status >= 400) {
            connection.errorStream ?: ByteArrayInputStream(ByteArray(0))
        } else {
            connection.inputStream
        }

        return WebResourceResponse(
            mimeType,
            null,
            status,
            if (status in 200..299) "OK" else "HTTP $status",
            responseHeaders,
            stream
        )
    }

    private fun buildDebugReport(
        hydraxUrl: String,
        referer: String,
        requests: List<CapturedRequest>,
        events: List<String>,
        jsResult: String
    ): String {
        val streamRequests = requests.filter { isStreamUrl(it.url) }

        val checks = listOf(
            Pair(requests.any { it.url.contains("abyssplayer.com/?v=", true) }, "Abyss iframe requested"),
            Pair(requests.any { it.url.contains("iamcdn", true) }, "iamcdn assets requested"),
            Pair(requests.any { it.url.contains("jwplayer", true) }, "JWPlayer assets requested"),
            Pair(events.any { it.contains("OPPA_HOOK_READY") } || jsResult.contains("OPPA_HOOK_READY"), "Hook injected"),
            Pair(events.any { it.contains("OPPA_SOURCE") } || jsResult.contains("OPPA_SOURCE"), "JWPlayer sources captured"),
            Pair(streamRequests.isNotEmpty(), "Stream request captured"),
            Pair(streamRequests.any { it.status == 200 || it.status == 206 }, "Stream status 200/206"),
            Pair(streamRequests.any { it.status == 404 }, "Stream status 404"),
            Pair(streamRequests.any { it.cookie.isNotBlank() }, "Stream request has Cookie")
        ).joinToString("\n") {
            "[${if (it.first) "✓" else "✗"}] ${it.second}"
        }

        val requestText = requests
            .takeLast(30)
            .joinToString("\n\n") { request ->
                val headerText = request.headers
                    .entries
                    .joinToString("\n") { "  ${it.key}: ${it.value}" }
                    .ifBlank { "  none" }

                val responseText = request.responseHeaders
                    .entries
                    .take(10)
                    .joinToString("\n") { "  ${it.key}: ${it.value}" }
                    .ifBlank { "  none" }

                """
URL: ${request.url}
METHOD: ${request.method}
STATUS: ${request.status ?: "not captured"}
COOKIE: ${if (request.cookie.isNotBlank()) request.cookie else "none"}
REQUEST HEADERS:
$headerText
RESPONSE HEADERS:
$responseText
ERROR: ${request.error ?: "none"}
                """.trimIndent()
            }
            .ifBlank { "No matched requests captured" }

        val eventText = events
            .takeLast(40)
            .joinToString("\n")
            .ifBlank { "No hook events captured" }

        val cleanJs = jsResult
            .replace("\\n", " ")
            .replace("\\\"", "\"")
            .take(2500)

        return """
========== OPPA HYDRAX DEBUG v23 ==========

Hydrax URL:
$hydraxUrl

Referer:
$referer

Checks:
$checks

Captured Requests:
$requestText

Hook Events:
$eventText

Top Frame JS:
$cleanJs

==========================================
        """.trimIndent()
    }

    private fun isStreamUrl(requestUrl: String?): Boolean {
        val value = requestUrl?.lowercase().orEmpty()

        return value.contains("sssrr.org/sora/") ||
            value.contains("/sora/") ||
            value.contains(".m3u8") ||
            value.contains(".mp4")
    }

    private fun htmlEscape(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    private const val PROBE_JS = """
(function() {
  return JSON.stringify({
    href: location.href,
    iframeCount: document.querySelectorAll("iframe").length,
    iframeSrcs: Array.prototype.slice.call(document.querySelectorAll("iframe")).map(function(i) { return i.src; }),
    events: window.__oppaEvents || []
  });
})()
    """

    private const val HOOK_JS = """
<script>
(function() {
  function post(type, value) {
    try {
      var text = "OPPA_" + type + " = " + value;
      window.parent.postMessage(text, "*");
    } catch(e) {}
  }

  function sendSources(prefix, list) {
    try {
      if (!list) return;

      for (var i = 0; i < list.length; i++) {
        var source = list[i] || {};
        var label = source.label || source.name || "Unknown";
        var type = source.type || "";
        var file = source.file || source.url || "";

        if (file) {
          post("SOURCE", label + " | " + type + " | " + file);
        }
      }
    } catch(e) {
      post(prefix + "_SOURCE_ERROR", e.message);
    }
  }

  post("HOOK_READY", location.href);

  try {
    Object.defineProperty(window, "jwplayer", {
      configurable: true,
      set: function(value) {
        try { post("JWPLAYER_SET", typeof value); } catch(e) {}
        this.__oppaJwplayer = value;
      },
      get: function() {
        return this.__oppaJwplayer;
      }
    });
  } catch(e) {
    post("JWPLAYER_HOOK_ERROR", e.message);
  }

  try {
    var timer = setInterval(function() {
      try {
        if (typeof window.jwplayer === "function") {
          var player = window.jwplayer();

          if (player && player.getState) {
            post("JWPLAYER_STATE", player.getState());
          }

          if (player && player.getPlaylist) {
            var playlist = player.getPlaylist() || [];
            post("JWPLAYER_PLAYLIST_SIZE", playlist.length);

            for (var i = 0; i < playlist.length; i++) {
              var item = playlist[i] || {};
              sendSources("PLAYLIST_SOURCES", item.sources);
              sendSources("PLAYLIST_ALLSOURCES", item.allSources);
            }
          }

          if (player && player.getPlaylistItem) {
            var currentItem = player.getPlaylistItem() || {};
            sendSources("CURRENT_SOURCES", currentItem.sources);
            sendSources("CURRENT_ALLSOURCES", currentItem.allSources);
          }
        }

        var videos = document.querySelectorAll("video");
        for (var v = 0; v < videos.length; v++) {
          var src = videos[v].currentSrc || videos[v].src || "";
          if (src) post("VIDEO_SRC", src);
        }
      } catch(e) {}
    }, 1000);

    setTimeout(function() { clearInterval(timer); }, 21000);
  } catch(e) {
    post("TIMER_HOOK_ERROR", e.message);
  }
})();
</script>
    """

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 Chrome/139.0 Mobile Safari/537.36"
}
