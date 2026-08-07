package com.oppadrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class OppadramaProvider : MainAPI() {

    override var mainUrl = "http://45.11.57.192"
    override var name = "OppaDrama"
    override var lang = "id"

    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private var domainResolved = false

    private val headers = mapOf(
        "User-Agent" to
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Accept" to
            "text/html,application/xhtml+xml,application/xml;q=0.9," +
            "image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override val mainPage = mainPageOf(
        "series/?status=&type=&order=update" to "Latest Update",
        "series/?country%5B%5D=china&type=Drama&order=update" to "Drama Chinese",
        "series/?country%5B%5D=japan&type=Drama&order=update" to "Drama Jepang",
        "series/?country%5B%5D=south-korea&status=&type=Drama&order=update" to "Drama Korea",
        "series/?country%5B%5D=philippines&type=Drama&order=update" to "Drama Philippines",
        "series/?country%5B%5D=taiwan&type=Drama&order=update" to "Drama Taiwan",
        "series/?country%5B%5D=thailand&type=Drama&order=update" to "Drama Thailand",
        "series/?country%5B%5D=usa&type=Drama&order=update" to "Drama Western",
        "series/?country%5B%5D=china&status=&type=Movie&order=update" to "Chinese Movie",
        "series/?country%5B%5D=hong-kong&status=&type=Movie&order=update" to "Hong Kong Movie",
        "series/?country%5B%5D=india&status=&type=Movie&order=update" to "India Movie",
        "series/?country%5B%5D=japan&type=Movie&order=update" to "Japan Movie",
        "series/?country%5B%5D=south-korea&status=&type=Movie&order=update" to "Korean Movie",
        "series/?country%5B%5D=philippines&status=&type=Movie&order=update" to "Philippines Movie",
        "series/?country%5B%5D=taiwan&type=Movie&order=update" to "Taiwan Movie",
        "series/?country%5B%5D=thailand&type=Movie&order=update" to "Thailand Movie",
        "series/?country%5B%5D=united-states&status=&type=Movie&order=update" to "Western Movie"
    )

    private suspend fun resolveDomain() {
        if (domainResolved) return
        domainResolved = true

        val redirect = runCatching {
            val response = app.get(
                mainUrl,
                headers = headers,
                allowRedirects = false
            )
            response.headers["location"] ?: response.headers["Location"]
        }.getOrNull()?.trim()

        if (redirect.isNullOrBlank()) return

        val resolved = runCatching {
            when {
                redirect.startsWith("http://", true) ||
                    redirect.startsWith("https://", true) -> redirect

                redirect.startsWith("//") -> "https:$redirect"

                else -> URI(mainUrl).resolve(redirect).toString()
            }
        }.getOrNull()

        if (!resolved.isNullOrBlank()) {
            mainUrl = resolved.trimEnd('/')
        }
    }

    private fun buildUrl(path: String): String = when {
        path.startsWith("http://", true) ||
            path.startsWith("https://", true) -> path

        path.startsWith("/") -> "${mainUrl.trimEnd('/')}$path"

        else -> "${mainUrl.trimEnd('/')}/$path"
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        resolveDomain()

        val separator = if (request.data.contains("?")) "&" else "?"
        val url = buildUrl(request.data) + "${separator}page=$page"

        val document = app.get(url, headers = headers).document
        val typeHint = if (
            request.data.contains("type=Movie", ignoreCase = true)
        ) {
            TvType.Movie
        } else {
            TvType.TvSeries
        }

        val items = document
            .select("div.listupd article.bs, .listupd .bs")
            .mapNotNull { it.toSearchResult(typeHint) }

        return newHomePageResponse(
            HomePageList(request.name, items),
            hasNext = items.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(typeHint: TvType? = null): SearchResponse? {
        val anchor = selectFirst("a[href]") ?: return null
        val href = anchor.attr("href").trim()
        if (href.isBlank()) return null

        val url = fixUrl(href)
        val title = anchor.attr("title")
            .ifBlank { selectFirst(".tt")?.text().orEmpty() }
            .ifBlank { anchor.text() }
            .trim()

        if (title.isBlank()) return null

        val poster = selectFirst("img")
            ?.getImageUrl()
            ?.let(::fixUrlNull)

        val badgeText = selectFirst(".typez, .status, .epx")
            ?.text()
            .orEmpty()

        val type = typeHint ?: when {
            url.contains("/movie/", true) -> TvType.Movie
            badgeText.contains("movie", true) -> TvType.Movie
            else -> TvType.TvSeries
        }

        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, url, TvType.Movie) {
                posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                posterUrl = poster
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        resolveDomain()

        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val document = app.get(
            "${mainUrl.trimEnd('/')}/?s=$encoded",
            headers = headers
        ).document

        return document
            .select("div.listupd article.bs, .listupd .bs")
            .mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        resolveDomain()

        val document = app.get(url, headers = headers).document

        val title = document
            .selectFirst("h1.entry-title, h1")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("OppaDrama title not found")

        val poster = document
            .selectFirst("div.bigcontent img, .thumb img, .poster img")
            ?.getImageUrl()
            ?.let(::fixUrlNull)

        val description = document
            .select("div.entry-content p")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .takeIf { it.isNotBlank() }

        val typeText = document.infoValue("Tipe", "Type").orEmpty()
        val status = parseStatus(document.infoValue("Status"))
        val year = parseYear(
            document.infoValue("Dirilis", "Released", "Tahun", "Year")
                ?: document.selectFirst(".year")?.text()
        )
        val duration = parseDuration(
            document.infoValue("Durasi", "Duration")
        )

        val tags = document
            .select("div.genxed a, .genxed a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        val actors = document
            .select(
                "span:has(b:matchesOwn(Artis:)) a, " +
                    "span:has(b:matchesOwn(Cast:)) a, " +
                    ".spe span:has(b:matchesOwn(Artis:)) a"
            )
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        val rating = document
            .selectFirst("div.rating strong, .rating strong")
            ?.text()
            ?.let {
                Regex("""\d+(?:\.\d+)?""")
                    .find(it)
                    ?.value
                    ?.toDoubleOrNull()
            }

        val trailer = document
            .selectFirst("div.bixbox.trailer iframe, .trailer iframe")
            ?.getIframeUrl()
            ?.let { it.toAbsoluteUrl(url) }

        val recommendations = document
            .select("div.listupd article.bs, .listupd .bs")
            .mapNotNull { it.toSearchResult() }

        val episodes = document
            .select("div.eplister ul li a[href], .eplister ul li a[href]")
            .asReversed()
            .mapIndexed { index, anchor ->
                val episodeText = anchor
                    .selectFirst(".epl-num, .epl-title")
                    ?.text()
                    ?.trim()
                    .orEmpty()

                val episodeNumber = Regex("""\d+""")
                    .find(episodeText)
                    ?.value
                    ?.toIntOrNull()
                    ?: index + 1

                val episodeName = anchor
                    .selectFirst(".epl-title")
                    ?.text()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: "Episode $episodeNumber"

                newEpisode(fixUrl(anchor.attr("href"))) {
                    name = episodeName
                    episode = episodeNumber
                }
            }

        val isMovie = typeText.contains("movie", true) ||
            url.contains("/movie/", true)

        return if (isMovie) {
            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                episodes.firstOrNull()?.data ?: url
            ) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                if (duration != null) this.duration = duration
                if (rating != null) addScore(rating.toString(), 10)
                addActors(actors)
                addTrailer(trailer)
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
                plot = description
                this.tags = tags
                if (duration != null) this.duration = duration
                if (status != null) showStatus = status
                if (rating != null) addScore(rating.toString(), 10)
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        resolveDomain()

        val document = app.get(
            data,
            headers = headers,
            referer = mainUrl
        ).document

        val serverUrls = linkedSetOf<String>()

        document
            .select("div.player-embed iframe, .player-embed iframe")
            .forEach { iframe ->
                iframe.getIframeUrl()
                    ?.toAbsoluteUrl(data)
                    ?.takeIf { it.isNotBlank() }
                    ?.let(serverUrls::add)
            }

        document
            .select("select.mirror option[value]:not([disabled])")
            .forEach { option ->
                decodeMirror(option.attr("value"), data)
                    ?.takeIf { it.isNotBlank() }
                    ?.let(serverUrls::add)
            }

        document
            .select("div.dlbox li span.e a[href], .dlbox a[href]")
            .forEach { anchor ->
                anchor.attr("href")
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.toAbsoluteUrl(data)
                    ?.let(serverUrls::add)
            }

        serverUrls.forEach { serverUrl ->
            runCatching {
                loadExtractor(
                    serverUrl,
                    data,
                    subtitleCallback,
                    callback
                )
            }
        }

        return serverUrls.isNotEmpty()
    }

    private fun decodeMirror(value: String, baseUrl: String): String? {
        val cleaned = value.trim()
        if (cleaned.isBlank()) return null

        if (
            cleaned.startsWith("http://", true) ||
            cleaned.startsWith("https://", true) ||
            cleaned.startsWith("//") ||
            cleaned.startsWith("/")
        ) {
            return cleaned.toAbsoluteUrl(baseUrl)
        }

        return runCatching {
            val html = base64Decode(cleaned.replace("\\s".toRegex(), ""))
            Jsoup.parse(html)
                .selectFirst("iframe")
                ?.getIframeUrl()
                ?.toAbsoluteUrl(baseUrl)
        }.getOrNull()
    }

    private fun Document.infoValue(vararg labels: String): String? {
        val elements = select(
            "div.spe span, div.info-content span, " +
                ".spe span, .info-content span"
        )

        for (element in elements) {
            val fullText = element.text().trim()
            val boldText = element
                .selectFirst("b, strong")
                ?.text()
                ?.trim()
                ?.trimEnd(':')

            for (label in labels) {
                val matchesBold = boldText.equals(label, ignoreCase = true)
                val matchesText = fullText.startsWith(
                    "$label:",
                    ignoreCase = true
                )

                if (!matchesBold && !matchesText) continue

                return fullText
                    .replaceFirst(
                        Regex(
                            "^${Regex.escape(label)}\\s*:\\s*",
                            RegexOption.IGNORE_CASE
                        ),
                        ""
                    )
                    .trim()
                    .takeIf { it.isNotBlank() }
            }
        }

        return null
    }

    private fun parseStatus(value: String?): ShowStatus? = when {
        value.isNullOrBlank() -> null

        value.contains("ongoing", true) ||
            value.contains("berjalan", true) -> ShowStatus.Ongoing

        value.contains("completed", true) ||
            value.contains("complete", true) ||
            value.contains("tamat", true) -> ShowStatus.Completed

        else -> null
    }

    private fun parseYear(value: String?): Int? {
        return value
            ?.let { Regex("""(?:19|20)\d{2}""").find(it)?.value }
            ?.toIntOrNull()
    }

    private fun parseDuration(value: String?): Int? {
        if (value.isNullOrBlank()) return null

        val hours = Regex(
            """(\d+)\s*(?:hr|hour|hours|jam)""",
            RegexOption.IGNORE_CASE
        ).find(value)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0

        val minutes = Regex(
            """(\d+)\s*(?:min|mins|minute|minutes|menit)""",
            RegexOption.IGNORE_CASE
        ).find(value)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0

        return (hours * 60 + minutes).takeIf { it > 0 }
    }

    private fun Element.getImageUrl(): String? {
        return when {
            attr("data-src").isNotBlank() -> attr("data-src")
            attr("data-lazy-src").isNotBlank() -> attr("data-lazy-src")
            attr("srcset").isNotBlank() ->
                attr("srcset").substringBefore(",").trim().substringBefore(" ")

            attr("src").isNotBlank() -> attr("src")
            else -> null
        }
    }

    private fun Element.getIframeUrl(): String? {
        return when {
            attr("data-litespeed-src").isNotBlank() ->
                attr("data-litespeed-src")

            attr("data-src").isNotBlank() -> attr("data-src")
            attr("src").isNotBlank() -> attr("src")
            else -> null
        }
    }

    private fun String.toAbsoluteUrl(baseUrl: String): String? {
        val value = trim()
        if (value.isBlank()) return null

        return runCatching {
            when {
                value.startsWith("http://", true) ||
                    value.startsWith("https://", true) -> value

                value.startsWith("//") -> "https:$value"

                else -> URI(baseUrl).resolve(value).toString()
            }
        }.getOrNull()
    }
}
