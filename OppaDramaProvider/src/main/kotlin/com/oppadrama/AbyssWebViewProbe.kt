package com.oppadrama

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
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
import kotlin.coroutines.resume

object AbyssWebViewProbe {

    private const val MAX_WAIT_MS = 26000L

    data class AbyssStream(
        val label: String,
        val url: String,
        val headers: Map<String, String>
    )

    suspend fun extractFast(
        url: String,
        referer: String
    ): List<AbyssStream> = withContext(Dispatchers.Main) {
        val context = OppaRuntime.context
            ?: return@withContext emptyList()

        suspendCancellableCoroutine { continuation ->
            val handler = Handler(Looper.getMainLooper())
            val webView = WebView(context)
            val streams = linkedMapOf<String, AbyssStream>()

            fun safeDestroy() {
                runCatching {
                    handler.removeCallbacksAndMessages(null)
                    webView.stopLoading()
                    webView.loadUrl("about:blank")
                    webView.removeAllViews()
                    webView.destroy()
                }
            }

            fun finish() {
                handler.post {
                    if (continuation.isActive) {
                        val result = streams.values.toList()
                        Log.i(TAG, "OPPA_FAST_FINISH = streams=${result.size}")
                        safeDestroy()
                        continuation.resume(result)
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

            fun captureStream(request: WebResourceRequest?) {
                val requestUrl = request?.url?.toString()?.trim().orEmpty()
                if (!isStreamUrl(requestUrl)) return

                val fixedUrl = requestUrl.toAbsoluteStreamUrl()
                val headers = request?.requestHeaders
                    .orEmpty()
                    .toMutableMap()

                val cookie = runCatching {
                    CookieManager.getInstance().getCookie(requestUrl)
                }.getOrNull().orEmpty()

                if (cookie.isNotBlank()) {
                    headers["Cookie"] = cookie
                }

                Log.i(
                    TAG,
                    "OPPA_FAST_CAPTURE_STREAM = $fixedUrl | " +
                        headers.keys.joinToString(",")
                )

                streams[fixedUrl] = AbyssStream(
                    label = guessLabel(fixedUrl),
                    url = fixedUrl,
                    headers = headers
                )

                finish()
            }

            continuation.invokeOnCancellation {
                safeDestroy()
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

                        if (isUsefulDebugUrl(requestUrl)) {
                            Log.i(TAG, "OPPA_FAST_WEBVIEW_REQUEST = $requestUrl")
                        }

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

                        /*
                         * Capture the short-lived /sora/ URL immediately and block WebView
                         * from consuming it. Returning fast is the important part.
                         */
                        if (isStreamUrl(requestUrl)) {
                            captureStream(request)

                            return WebResourceResponse(
                                "video/mp4",
                                "UTF-8",
                                ByteArrayInputStream(ByteArray(0))
                            )
                        }

                        return super.shouldInterceptRequest(view, request)
                    }

                    @Deprecated("Deprecated in Android")
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        requestUrl: String?
                    ): Boolean = false

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean = false
                }

                val escapedUrl = htmlEscape(url)

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

                webView.loadDataWithBaseURL(
                    referer,
                    wrapper,
                    "text/html",
                    "UTF-8",
                    null
                )

                listOf(
                    1800L,
                    3200L,
                    5000L,
                    7200L,
                    9500L,
                    12500L,
                    15500L,
                    18500L,
                    21500L
                ).forEach { delay ->
                    handler.postDelayed({
                        Log.i(TAG, "OPPA_FAST_CLICK = $delay")
                        clickWebView()
                    }, delay)
                }

                handler.postDelayed({
                    finish()
                }, MAX_WAIT_MS)
            }

            runCatching {
                setup()
            }.onFailure {
                finish()
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

        return WebResourceResponse(
            "text/html",
            "UTF-8",
            ByteArrayInputStream(html.toByteArray(Charsets.UTF_8))
        ).apply {
            responseHeaders = mapOf(
                "Access-Control-Allow-Origin" to "*"
            )
        }
    }

    private fun isStreamUrl(requestUrl: String?): Boolean {
        val value = requestUrl?.lowercase().orEmpty()

        return value.contains("sssrr.org/sora/") ||
            value.contains("/sora/") ||
            value.contains(".m3u8") ||
            value.contains(".mp4")
    }

    private fun guessLabel(url: String): String {
        val value = url.lowercase()

        return when {
            value.contains("/1421764806/") -> "720p"
            value.contains("/677311756/") -> "360p"
            value.contains("1080") -> "1080p"
            value.contains("720") -> "720p"
            value.contains("480") -> "480p"
            value.contains("360") -> "360p"
            else -> "Auto"
        }
    }

    private fun String.toAbsoluteStreamUrl(): String {
        val value = trim()

        return when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("http", true) -> value
            value.startsWith("/") -> "https://abyssplayer.com$value"
            else -> value
        }
    }

    private fun htmlEscape(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    private fun isUsefulDebugUrl(requestUrl: String?): Boolean {
        val value = requestUrl?.lowercase().orEmpty()

        return value.contains("abyss") ||
            value.contains("iamcdn") ||
            value.contains("jwplayer") ||
            value.contains("sssrr") ||
            value.contains("/sora/") ||
            value.contains(".m3u8") ||
            value.contains(".mp4")
    }

    private const val TAG = "OppaDrama"

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 Chrome/139.0 Mobile Safari/537.36"
}
