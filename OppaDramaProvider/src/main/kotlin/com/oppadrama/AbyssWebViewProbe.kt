package com.oppadrama

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
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

    private const val MAX_WAIT_MS = 18000L

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
                captures = emptyList(),
                jsResult = "OppaRuntime.context is null"
            )

        suspendCancellableCoroutine { continuation ->
            val requests = Collections.synchronizedList(mutableListOf<String>())
            val captures = Collections.synchronizedList(mutableListOf<String>())
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

            fun addCapture(value: String?) {
                val clean = value?.trim().orEmpty()
                if (clean.isBlank()) return

                if (!captures.contains(clean)) {
                    captures.add(clean)
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
                    val requestSnapshot = synchronized(requests) { requests.toList() }
                    val captureSnapshot = synchronized(captures) { captures.toList() }

                    val allText = (requestSnapshot + captureSnapshot + listOf(jsResult))
                        .joinToString("\n")

                    val checks: List<Pair<Boolean, String>> = listOf(
                        Pair(true, "Plugin context available"),
                        Pair(
                            requestSnapshot.any { it.contains("abyssplayer.com/?v=", true) },
                            "Original Abyss iframe requested"
                        ),
                        Pair(
                            !requestSnapshot.any { it.equals("https://abyss.to/", true) },
                            "Not forced to abyss.to landing page"
                        ),
                        Pair(
                            requestSnapshot.any { it.contains("iamcdn", true) },
                            "iamcdn assets loaded"
                        ),
                        Pair(
                            requestSnapshot.any { it.contains("jwplayer", true) },
                            "JWPlayer assets requested"
                        ),
                        Pair(
                            captureSnapshot.any { it.contains("OPPA_HOOK_READY", true) },
                            "Hook injected inside Abyss iframe"
                        ),
                        Pair(
                            captureSnapshot.any { it.contains("fetch", true) },
                            "fetch hook captured"
                        ),
                        Pair(
                            captureSnapshot.any { it.contains("xhr", true) },
                            "XHR hook captured"
                        ),
                        Pair(
                            allText.contains(".m3u8", true),
                            "m3u8 seen"
                        ),
                        Pair(
                            allText.contains(".mp4", true),
                            "mp4 seen"
                        ),
                        Pair(
                            allText.contains("sora", true) ||
                                allText.contains("sssrr", true),
                            "Abyss segment/domain seen"
                        ),
                        Pair(
                            allText.contains("blob:", true) ||
                                captureSnapshot.any { it.contains("createObjectURL", true) },
                            "Blob or MSE activity seen"
                        )
                    )

                    continuation.resume(
                        debugText(
                            url = url,
                            stage = stage,
                            checks = checks,
                            requests = requestSnapshot,
                            captures = captureSnapshot,
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
                    ): WebResourceResponse? {
                        val requestUrl = request?.url?.toString()
                        addRequest(requestUrl)

                        if (
                            requestUrl?.contains("abyssplayer.com/?v=", true) == true
                        ) {
                            return runCatching {
                                injectIntoAbyssPage(
                                    pageUrl = requestUrl,
                                    referer = referer
                                )
                            }.getOrNull()
                        }

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

                val escapedUrl = url
                    .replace("&", "&amp;")
                    .replace("\"", "&quot;")

                val wrapper = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <meta name="viewport" content="width=device-width, initial-scale=1.0">
                        <script>
                            window.__oppaCaptures = [];
                            window.addEventListener("message", function(event) {
                                try {
                                    var data = event.data;
                                    if (!data) return;
                                    if (typeof data === "string") {
                                        if (data.indexOf("OPPA_") >= 0) {
                                            window.__oppaCaptures.push(data);
                                        }
                                    } else if (data.oppaCapture) {
                                        window.__oppaCaptures.push(JSON.stringify(data));
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

                addRequest(url)

                webView.loadDataWithBaseURL(
                    referer,
                    wrapper,
                    "text/html",
                    "UTF-8",
                    null
                )

                handler.postDelayed({ clickWebView() }, 3000L)
                handler.postDelayed({ clickWebView() }, 6000L)
                handler.postDelayed({ clickWebView() }, 9000L)
                handler.postDelayed({ clickWebView() }, 12000L)

                handler.postDelayed({
                    runCatching {
                        webView.evaluateJavascript(PROBE_JS) { result ->
                            val capturesFromTop = extractCaptures(result)
                            capturesFromTop.forEach { addCapture(it) }

                            finish(
                                stage = "Hook probe completed",
                                jsResult = result ?: "null"
                            )
                        }
                    }.onFailure {
                        finish(
                            stage = "Hook probe failed",
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

    private fun injectIntoAbyssPage(
        pageUrl: String,
        referer: String
    ): WebResourceResponse {
        val connection = URL(pageUrl).openConnection() as HttpURLConnection

        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = true
        connection.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 Chrome/139.0 Mobile Safari/537.36"
        )
        connection.setRequestProperty("Referer", referer)
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        connection.setRequestProperty("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8")
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.connectTimeout = 10000
        connection.readTimeout = 10000

        val html = connection.inputStream.bufferedReader().use { it.readText() }

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

    private fun extractCaptures(jsResult: String?): List<String> {
        val value = jsResult ?: return emptyList()

        return Regex("OPPA_[^\\\\\"]+")
            .findAll(value)
            .map { it.value }
            .toList()
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
        captures: List<String>,
        jsResult: String
    ): String {
        val checkText = checks.joinToString("\n") { pair ->
            "[${if (pair.first) "✓" else "✗"}] ${pair.second}"
        }

        val requestText = requests
            .take(25)
            .joinToString("\n") { it.take(180) }
            .ifBlank { "No matched request captured" }

        val captureText = captures
            .take(30)
            .joinToString("\n") { it.take(220) }
            .ifBlank { "No hook capture received" }

        val cleanJs = jsResult
            .replace("\\n", " ")
            .replace("\\\"", "\"")
            .take(2200)

        return """
========== OPPA HOOK DEBUG ==========

Hydrax URL:
$url

Stage:
$stage

Checks:
$checkText

Captured Requests:
$requestText

Hook Captures:
$captureText

Top Frame JS Result:
$cleanJs

=====================================
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
  result.captures = window.__oppaCaptures || [];
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

    private const val HOOK_JS = """
<script>
(function() {
  function post(type, value) {
    try {
      var text = "OPPA_" + type + " = " + value;
      window.parent.postMessage(text, "*");
    } catch(e) {}
  }

  post("HOOK_READY", location.href);

  try {
    var originalFetch = window.fetch;
    window.fetch = function() {
      try {
        post("FETCH", arguments[0] && arguments[0].url ? arguments[0].url : String(arguments[0]));
      } catch(e) {}
      return originalFetch.apply(this, arguments).then(function(response) {
        try { post("FETCH_RESPONSE", response.url + " | " + response.status); } catch(e) {}
        return response;
      });
    };
  } catch(e) {
    post("FETCH_HOOK_ERROR", e.message);
  }

  try {
    var originalOpen = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function(method, url) {
      try { this.__oppaUrl = url; post("XHR_OPEN", method + " " + url); } catch(e) {}
      return originalOpen.apply(this, arguments);
    };

    var originalSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.send = function() {
      try {
        var xhr = this;
        xhr.addEventListener("load", function() {
          try { post("XHR_LOAD", xhr.__oppaUrl + " | " + xhr.status); } catch(e) {}
        });
      } catch(e) {}
      return originalSend.apply(this, arguments);
    };
  } catch(e) {
    post("XHR_HOOK_ERROR", e.message);
  }

  try {
    var originalCreateObjectURL = URL.createObjectURL;
    URL.createObjectURL = function(obj) {
      var blobUrl = originalCreateObjectURL.apply(this, arguments);
      try { post("CREATE_OBJECT_URL", blobUrl + " | " + Object.prototype.toString.call(obj)); } catch(e) {}
      return blobUrl;
    };
  } catch(e) {
    post("OBJECT_URL_HOOK_ERROR", e.message);
  }

  try {
    var originalAppendBuffer = SourceBuffer.prototype.appendBuffer;
    SourceBuffer.prototype.appendBuffer = function(buffer) {
      try {
        post("APPEND_BUFFER", buffer ? buffer.byteLength : 0);
      } catch(e) {}
      return originalAppendBuffer.apply(this, arguments);
    };
  } catch(e) {
    post("SOURCEBUFFER_HOOK_ERROR", e.message);
  }

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
          post("JWPLAYER_STATE", player && player.getState ? player.getState() : "no-state");

          if (player && player.getPlaylist) {
            post("JWPLAYER_PLAYLIST", JSON.stringify(player.getPlaylist()).slice(0, 1000));
          }
        }

        var videos = document.querySelectorAll("video");
        for (var i = 0; i < videos.length; i++) {
          post("VIDEO_SRC", (videos[i].currentSrc || videos[i].src || "no-src"));
        }
      } catch(e) {}
    }, 1000);

    setTimeout(function() { clearInterval(timer); }, 16000);
  } catch(e) {
    post("TIMER_HOOK_ERROR", e.message);
  }
})();
</script>
    """
}
