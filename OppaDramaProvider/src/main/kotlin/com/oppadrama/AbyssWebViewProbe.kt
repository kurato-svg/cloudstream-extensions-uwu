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

    data class AbyssStream(
        val label: String,
        val url: String
    )

    suspend fun extract(
        url: String,
        referer: String
    ): List<AbyssStream> = withContext(Dispatchers.Main) {
        val context = OppaRuntime.context
            ?: return@withContext emptyList()

        suspendCancellableCoroutine { continuation ->
            val captures = Collections.synchronizedList(mutableListOf<String>())
            val handler = Handler(Looper.getMainLooper())
            val webView = WebView(context)

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

            fun finish(jsResult: String?) {
                runCatching {
                    handler.removeCallbacksAndMessages(null)
                    webView.stopLoading()
                    webView.loadUrl("about:blank")
                    webView.removeAllViews()
                    webView.destroy()
                }

                if (continuation.isActive) {
                    extractCaptures(jsResult)
                        .forEach { addCapture(it) }

                    val snapshot = synchronized(captures) { captures.toList() }
                    continuation.resume(parseStreams(snapshot))
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
                webView.settings.userAgentString = USER_AGENT

                webView.webChromeClient = WebChromeClient()

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(
                        view: WebView?,
                        requestUrl: String?,
                        favicon: Bitmap?
                    ) = Unit

                    override fun onPageFinished(
                        view: WebView?,
                        requestUrl: String?
                    ) = Unit

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val requestUrl = request?.url?.toString()

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
                    ): Boolean = shouldBlockNavigation(requestUrl)

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean = shouldBlockNavigation(request?.url?.toString())
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
                finish(null)
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

    private fun parseStreams(captures: List<String>): List<AbyssStream> {
        val results = linkedMapOf<String, AbyssStream>()

        captures.forEach { line ->
            if (!line.contains("OPPA_SOURCE", true)) return@forEach

            val payload = line.substringAfter("=", "").trim()
            val parts = payload.split("|").map { it.trim() }

            val label = parts.getOrNull(0)
                ?.takeIf { it.isNotBlank() }
                ?: "Unknown"

            val file = parts.getOrNull(2)
                ?.takeIf { it.isNotBlank() }
                ?: return@forEach

            results[file] = AbyssStream(
                label = label,
                url = file
            )
        }

        if (results.isNotEmpty()) {
            return results.values.toList()
        }

        /*
         * Fallback: parse older OPPA_JWPLAYER_PLAYLIST captures.
         */
        captures.forEach { line ->
            Regex(
                """"label"\s*:\s*"([^"]+)".*?"file"\s*:\s*"([^"]+)"""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            ).findAll(line)
                .forEach { match ->
                    val label = match.groupValues.getOrNull(1)?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?: "Unknown"

                    val file = match.groupValues.getOrNull(2)?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?: return@forEach

                    results[file] = AbyssStream(
                        label = label,
                        url = file
                    )
                }
        }

        return results.values.toList()
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

    private const val PROBE_JS = """
(function() {
  return JSON.stringify({
    href: location.href,
    captures: window.__oppaCaptures || []
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
    XMLHttpRequest.prototype.open = function(method, requestUrl) {
      try { this.__oppaUrl = requestUrl; post("XHR_OPEN", method + " " + requestUrl); } catch(e) {}
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

    setTimeout(function() { clearInterval(timer); }, 17000);
  } catch(e) {
    post("TIMER_HOOK_ERROR", e.message);
  }
})();
</script>
    """

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 Chrome/139.0 Mobile Safari/537.36"
}
