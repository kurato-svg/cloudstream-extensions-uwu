package com.oppadrama

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.util.Base64

class OppadramaProvider : MainAPI() {

    override var mainUrl = "http://45.11.57.192"
    override var name = "OppaDrama"
    override var lang = "id"
    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    private var humanCookie: String? = null
    private var checkedAddress = false

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
    )

    override val mainPage = mainPageOf(
        "series/?status=&type=&order=update" to "Latest Update",
        "series/?status=&type=Drama&order=update" to "Drama",
        "series/?type=Movie&order=update" to "Movie",
        "series/?country%5B%5D=south-korea&type=Drama&order=update" to "Korea",
        "series/?country%5B%5D=china&type=Drama&order=update" to "China",
        "series/?country%5B%5D=japan&type=Drama&order=update" to "Japan",
        "series/?country%5B%5D=thailand&type=Drama&order=update" to "Thailand"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = getSiteDocument(
            buildMainPageUrl(
                page,
                request.data
            )
        )

        val items = document
            .select("div.listupd article.bs, div.listupd article.stylefor")
            .mapNotNull { it.toSearchResult() }

        val hasNext = document.selectFirst("div.hpage a.r") != null

        return newHomePageResponse(
            HomePageList(
                request.name,
                items
            ),
            hasNext
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val document = getSiteDocument("$mainUrl/?s=$encoded")

        return document
            .select("div.listupd article.bs, div.listupd article.stylefor")
            .mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = getSiteDocument(url)

        val title = document.titleText()
            ?: throw ErrorLoadingException("Title not found")

        val poster = document.poster()
        val plot = document.descriptionText()
        val tags = document.tagsList()

        val year = document.getInfo("Tahun")?.safeYear()
            ?: document.getInfo("Year")?.safeYear()
            ?: document.selectFirst(".year")?.text()?.safeYear()

        val status = parseStatus(
            document.getInfo("Status")
                ?: document.text()
        )

        val episodes = document.episodes()

        val isMovie = url.contains("/movie-", true) ||
            document.getInfo("Tipe")?.contains("Movie", true) == true ||
            document.getInfo("Type")?.contains("Movie", true) == true ||
            episodes.isEmpty()

        return if (isMovie) {
            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {
                posterUrl = poster
                this.year = year
                this.tags = tags
                plot?.let { this.plot = it }
            }
        } else {
            newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes
            ) {
                posterUrl = poster
                this.year = year
                this.tags = tags
                plot?.let { this.plot = it }
                status?.let { showStatus = it }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = getSiteDocument(data)

        val rawLinks = linkedSetOf<String>()

        document.select("div.player-embed iframe")
            .forEach { iframe ->
                iframe.getIframeUrl()
                    ?.toAbsoluteUrl(data)
                    ?.also { rawLinks.add(it) }
            }

        document.select("select.mirror option[value]")
            .forEach { option ->
                decodeMirror(
                    option.attr("value"),
                    option.text(),
                    data
                ).forEach { link ->
                    rawLinks.add(link)
                }
            }

        document.select("div.dlbox a[href]")
            .forEach { anchor ->
                anchor.attr("href")
                    .trim()
                    .takeIf { it.startsWith("http", true) }
                    ?.also { rawLinks.add(it) }
            }

        rawLinks.forEach {
            Log.i(TAG, "OPPA_RAW = $it")
        }

        val sortedLinks = rawLinks
            .filter { it.isNotBlank() }
            .distinct()
            .sortedBy { it.priorityScore() }

        sortedLinks.forEach {
            Log.i(TAG, "OPPA_SORTED = $it")
        }

        for (link in sortedLinks) {
            if (
                link.contains("abyss", true) ||
                link.contains("hydrax", true)
            ) {
                val streams = runCatching {
                    AbyssWebViewProbe.extract(
                        url = link,
                        referer = data
                    )
                }.onFailure {
                    Log.e(TAG, "OPPA_ABYSS_EXTRACT_FAILED = ${it.message}", it)
                }.getOrDefault(emptyList())

                streams.forEach { stream ->
                    val fixedUrl = stream.url.toAbsoluteStreamUrl()

                    Log.i(TAG, "OPPA_ABYSS_LINK = ${stream.label} | $fixedUrl")
                    Log.i(
                        TAG,
                        "OPPA_ABYSS_HEADER_KEYS = ${stream.label} | " +
                            stream.headers.keys.joinToString(",") +
                            " | cookie=${if (stream.headers.containsKey("Cookie")) "yes" else "no"}"
                    )

                    callback(
                        newExtractorLink(
                            source = "Abyss",
                            name = "Abyss ${stream.label}",
                            url = fixedUrl
                        ) {
                            this.referer = link
                            this.quality = getQualityFromName(stream.label)
                            this.headers = stream.headers
                                .toMutableMap()
                                .apply {
                                    remove("Host")
                                    remove("host")
                                    remove("Connection")
                                    remove("connection")
                                    remove("Accept-Encoding")
                                    remove("accept-encoding")
                                    remove("Range")
                                    remove("range")

                                    put("Referer", link)
                                    put("Origin", "https://abyssplayer.com")
                                    put("User-Agent", USER_AGENT)
                                    put("Accept", get("Accept") ?: "*/*")
                                }
                        }
                    )
                }

                if (streams.isNotEmpty()) {
                    return true
                }
            }

            runCatching {
                loadExtractor(
                    link,
                    data,
                    subtitleCallback,
                    callback
                )
            }.onFailure {
                Log.e(TAG, "OPPA_EXTRACTOR_FAILED = $link | ${it.message}")
            }
        }

        return sortedLinks.isNotEmpty()
    }

    private fun buildMainPageUrl(page: Int, data: String): String {
        return if (data.isBlank()) {
            if (page == 1) {
                mainUrl
            } else {
                "$mainUrl/page/$page/"
            }
        } else {
            val base = "$mainUrl/$data"

            if (page <= 1) {
                base
            } else {
                val separator = if (base.contains("?")) "&" else "?"
                "${base}${separator}page=$page"
            }
        }
    }

    private suspend fun getSiteDocument(url: String): Document {
        ensureAddress()
        ensureHumanCookie()

        return app.get(
            normalizeSiteUrl(url),
            headers = verifiedHeaders(),
            referer = mainUrl
        ).document
    }

    private suspend fun ensureAddress() {
        if (checkedAddress) return
        checkedAddress = true

        val resolved = runCatching {
            val response = app.get(
                "https://oppa.biz",
                headers = headers,
                allowRedirects = false
            )

            val location = response.headers["Location"]
                ?: response.headers["location"]

            when {
                !location.isNullOrBlank() && location.startsWith("http", true) ->
                    location.trimEnd('/')

                else -> {
                    val body = response.text
                    Regex("""https?://(?:\d{1,3}\.){3}\d{1,3}""")
                        .find(body)
                        ?.value
                        ?.trimEnd('/')
                }
            }
        }.getOrNull()

        if (!resolved.isNullOrBlank()) {
            mainUrl = resolved
        }
    }

    private suspend fun ensureHumanCookie() {
        if (!humanCookie.isNullOrBlank()) return

        val response = app.get(
            "$mainUrl/?verify_human=1",
            headers = headers,
            referer = mainUrl,
            allowRedirects = false
        )

        humanCookie = response.headers["Set-Cookie"]
            ?.substringBefore(";")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "user_is_human=true"
    }

    private fun verifiedHeaders(): Map<String, String> {
        val cookie = humanCookie

        return if (cookie.isNullOrBlank()) {
            headers
        } else {
            headers + mapOf(
                "Cookie" to cookie,
                "Referer" to "$mainUrl/",
                "Origin" to mainUrl
            )
        }
    }

    private fun normalizeSiteUrl(url: String): String {
        val value = url.trim()

        if (value.startsWith(mainUrl, true)) return value

        return when {
            value.startsWith("http://45.11.57.192", true) ||
                value.startsWith("https://oppa.biz", true) ||
                value.startsWith("http://oppa.biz", true) -> {
                val uri = URI(value)
                "$mainUrl${uri.rawPath ?: "/"}${uri.rawQuery?.let { "?$it" } ?: ""}"
            }

            else -> value
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a[href]") ?: return null
        val href = fixUrl(anchor.attr("href"))

        val title = selectFirst(".tt")
            ?.ownText()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: anchor.attr("title")
                .trim()
                .takeIf { it.isNotBlank() }
            ?: selectFirst("h2")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: return null

        val poster = selectFirst("img")
            ?.getImageUrl()
            ?.let(::fixUrl)

        val badge = selectFirst(".typez, .tt span")
            ?.text()
            ?.lowercase()
            ?: ""

        val isMovie = badge.contains("movie") ||
            href.contains("/movie-", true)

        return if (isMovie) {
            newMovieSearchResponse(
                title,
                href,
                TvType.Movie
            ) {
                posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(
                title,
                href,
                TvType.TvSeries
            ) {
                posterUrl = poster
            }
        }
    }

    private fun decodeMirror(
        value: String,
        label: String,
        referer: String
    ): List<String> {
        val cleaned = value.trim()
        val cleanLabel = label.trim()

        if (cleaned.isBlank()) {
            return emptyList()
        }

        if (
            cleaned.startsWith("http", true) ||
            cleaned.startsWith("//") ||
            cleaned.startsWith("/")
        ) {
            return cleaned.toAbsoluteUrl(referer)?.let { listOf(it) }
                ?: emptyList()
        }

        val decoded = decodeBase64(cleaned)
            ?: run {
                Log.e(TAG, "OPPA_DECODE_FAILED label=$cleanLabel")
                return emptyList()
            }

        Log.i(TAG, "OPPA_DECODE_LABEL = $cleanLabel")
        Log.i(TAG, "OPPA_DECODE_HTML = ${decoded.take(500)}")

        val lowerLabel = cleanLabel.lowercase()
        val results = linkedSetOf<String>()

        /*
         * For this diagnostic version, skip mirrors that the website itself
         * cannot play. This avoids WebViewResolver wasting 15 to 30 seconds
         * on dead StreamSB/GDrive routes and lets us see Hydrax clearly.
         */
        if (
            lowerLabel.contains("streamsb") ||
            lowerLabel.contains("gdrive") ||
            lowerLabel.contains("google")
        ) {
            Log.i(TAG, "OPPA_SKIP_MIRROR label=$cleanLabel")
            return emptyList()
        }

        Jsoup.parse(decoded)
            .select("iframe")
            .mapNotNull { it.getIframeUrl() }
            .mapNotNull { it.toAbsoluteUrl(referer) }
            .forEach { results.add(it) }

        val hydraxId = shortcodeId(decoded, "Hydrax")
            ?: if (lowerLabel.contains("hydrax")) {
                anyShortcodeId(decoded)
            } else {
                null
            }

        if (!hydraxId.isNullOrBlank()) {
            hydraxCandidates(hydraxId)
                .forEach { results.add(it) }
        }

        Regex("""https?://[^\s'"<>]+""")
            .findAll(decoded)
            .map { it.value }
            .mapNotNull { it.toAbsoluteUrl(referer) }
            .forEach { results.add(it) }

        results.forEach {
            Log.i(TAG, "OPPA_MIRROR = $it")
        }

        return results.toList()
    }

    private fun decodeBase64(text: String): String? {
        return runCatching {
            val compact = text.replace("\\s".toRegex(), "")
            val normalized = compact.padEnd(
                compact.length + (4 - compact.length % 4) % 4,
                '='
            )

            String(
                Base64.getDecoder().decode(normalized),
                Charsets.UTF_8
            )
        }.getOrNull()
    }

    private fun shortcodeId(
        text: String,
        name: String
    ): String? {
        return Regex(
            """\[$name\s+id=['"]([^'"]+)['"]""",
            RegexOption.IGNORE_CASE
        ).find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun anyShortcodeId(text: String): String? {
        return Regex(
            """id=['"]([^'"]+)['"]""",
            RegexOption.IGNORE_CASE
        ).find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun streamSbCandidates(id: String): List<String> {
        return listOf(
            "https://sbembed1.com/e/$id.html",
            "https://sbembed4.com/e/$id.html",
            "https://sbvideo.net/e/$id.html",
            "https://viewsb.com/e/$id",
            "https://watchsb.com/e/$id",
            "https://embedsb.com/e/$id",
            "https://playersb.com/e/$id",
            "https://streamsb.net/e/$id",
            "https://streamsb.com/e/$id",
            "https://sbembed.com/e/$id",
            "https://sbplay.org/e/$id",
            "https://streamsss.net/e/$id"
        )
    }

    private fun hydraxCandidates(id: String): List<String> {
        return listOf(
            "https://abyssplayer.com/?v=$id"
        )
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

    private fun String.priorityScore(): Int {
        val value = lowercase()

        return when {
            value.contains("vidhide") ||
                value.contains("earnvid") -> 0

            value.contains("abyss.to") ||
                value.contains("abyssplayer") ||
                value.contains("hydrax") -> 1

            value.contains("sbembed1") ||
                value.contains("sbembed4") ||
                value.contains("sbvideo") ||
                value.contains("streamsb") ||
                value.contains("viewsb") ||
                value.contains("watchsb") ||
                value.contains("embedsb") ||
                value.contains("playersb") ||
                value.contains("sbembed") ||
                value.contains("sbplay") ||
                value.contains("streamsss") -> 2

            value.contains("emturbovid") -> 3

            value.contains("minochinos") ||
                value.contains("filelions") ||
                value.contains("filemoon") -> 4

            value.contains("drive.google") -> 5

            else -> 9
        }
    }

    private fun Element.getImageUrl(): String? {
        return when {
            attr("data-src").isNotBlank() -> attr("data-src")
            attr("data-lazy-src").isNotBlank() -> attr("data-lazy-src")
            attr("data-original").isNotBlank() -> attr("data-original")
            attr("src").isNotBlank() -> attr("src")
            attr("srcset").isNotBlank() ->
                attr("srcset").substringBefore(",").substringBefore(" ")
            else -> null
        }
    }

    private fun Element.getIframeUrl(): String? {
        return when {
            attr("data-litespeed-src").isNotBlank() -> attr("data-litespeed-src")
            attr("data-src").isNotBlank() -> attr("data-src")
            attr("src").isNotBlank() -> attr("src")
            else -> null
        }
    }

    private fun String.toAbsoluteUrl(referer: String): String? {
        val value = trim()
        if (value.isBlank()) return null

        return runCatching {
            when {
                value.startsWith("http://", true) ||
                    value.startsWith("https://", true) -> value
                value.startsWith("//") -> "https:$value"
                value.startsWith("/") -> "${mainUrl.trimEnd('/')}$value"
                else -> URI(referer).resolve(value).toString()
            }
        }.getOrNull()
    }

    private fun Document.getInfo(key: String): String? {
        return select("div.spe span")
            .firstOrNull {
                it.text().startsWith("$key:", true)
            }
            ?.text()
            ?.substringAfter(":")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun parseStatus(text: String?): ShowStatus? {
        val value = text?.lowercase() ?: return null

        return when {
            value.contains("completed") -> ShowStatus.Completed
            value.contains("ongoing") -> ShowStatus.Ongoing
            else -> null
        }
    }

    private fun String.safeInt(): Int? {
        return Regex("\\d+")
            .find(this)
            ?.value
            ?.toIntOrNull()
    }

    private fun String.safeYear(): Int? {
        return Regex("(19|20)\\d{2}")
            .find(this)
            ?.value
            ?.toIntOrNull()
    }

    private fun Document.episodes(): List<Episode> {
        return select("div.eplister li a")
            .reversed()
            .mapIndexed { index, element ->
                val episodeNumber = element
                    .selectFirst(".epl-num")
                    ?.text()
                    ?.safeInt()
                    ?: index + 1

                newEpisode(
                    fixUrl(element.attr("href"))
                ) {
                    episode = episodeNumber
                    name = element
                        .selectFirst(".epl-title")
                        ?.text()
                        ?.trim()
                }
            }
    }

    private fun Document.poster(): String? {
        return selectFirst(".thumb img, .poster img, .bigcontent img")
            ?.getImageUrl()
            ?.let(::fixUrl)
    }

    private fun Document.titleText(): String? {
        return selectFirst("h1.entry-title, h1")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun Document.descriptionText(): String? {
        return select(".entry-content p")
            .joinToString("\n") { it.text().trim() }
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private fun Document.tagsList(): List<String> {
        return select(".genxed a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
    }

    companion object {
        private const val TAG = "OppaDrama"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 Chrome/139.0 Mobile Safari/537.36"
    }
}
