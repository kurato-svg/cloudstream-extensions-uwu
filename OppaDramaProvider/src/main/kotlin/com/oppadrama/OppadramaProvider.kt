package com.oppadrama

import com.lagradost.cloudstream3.*
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
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/139.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
    )

    private val webViewResolver = WebViewResolver(
        Regex("""45\.11\.57\.192|oppa\.biz""")
    )

    override val mainPage = mainPageOf(
        "" to "Latest Update",
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
        val url = buildMainPageUrl(request.data, page)

        val document = getPage(url)

        val items = document
            .selectCards()
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        val hasNext = document.selectFirst("div.hpage a.r, .pagination a.next, a.next") != null

        return newHomePageResponse(
            HomePageList(request.name, items),
            hasNext
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())

        val document = getPage("$mainUrl/?s=$encoded")

        return document
            .selectCards()
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a[href]") ?: return null
        val href = anchor.attr("href").trim().takeIf { it.isNotBlank() } ?: return null
        val fixedUrl = fixUrl(href)

        val title = anchor.attr("title")
            .ifBlank { selectFirst(".tt")?.ownText().orEmpty() }
            .ifBlank { selectFirst("h2")?.text().orEmpty() }
            .trim()

        if (title.isBlank()) return null

        val poster = selectFirst("img")
            ?.getImageUrl()
            ?.let { fixUrl(it) }

        val badge = selectFirst(".typez")
            ?.text()
            ?.lowercase()
            .orEmpty()

        val isMovie = badge.contains("movie") || fixedUrl.contains("/movie-", true)

        return if (isMovie) {
            newMovieSearchResponse(
                title,
                fixedUrl,
                TvType.Movie
            ) {
                posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(
                title,
                fixedUrl,
                TvType.TvSeries
            ) {
                posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val firstDocument = getPage(url)

        val isEpisodePage = url.contains("-episode-", true)
        val isMovie = url.contains("/movie-", true)

        val seriesUrl = if (!isMovie && isEpisodePage) {
            firstDocument.findSeriesUrl()
        } else {
            null
        }

        val document = if (!seriesUrl.isNullOrBlank()) {
            getPage(seriesUrl)
        } else {
            firstDocument
        }

        val loadUrl = seriesUrl ?: url

        val title = document.titleText()
            ?: firstDocument.titleText()
            ?: throw ErrorLoadingException("Title not found")

        val poster = document.poster()
            ?: firstDocument.poster()

        val plot = document.descriptionText()
            ?: firstDocument.descriptionText()

        val tags = document.tagsList()

        val year = parseYear(
            document.getInfo("Tahun")
                ?: document.getInfo("Year")
                ?: document.text()
        )

        val status = parseStatus(
            document.getInfo("Status")
                ?: document.text()
        )

        val episodes = document.episodes().ifEmpty {
            if (!isMovie) {
                listOf(
                    newEpisode(url) {
                        name = firstDocument.titleText() ?: title
                        episode = firstDocument.titleText()?.safeInt()
                    }
                )
            } else {
                emptyList()
            }
        }

        return if (isMovie) {
            newMovieLoadResponse(
                title,
                loadUrl,
                TvType.Movie,
                url
            ) {
                posterUrl = poster
                this.year = year
                this.tags = tags
                this.plot = plot
            }
        } else {
            newTvSeriesLoadResponse(
                title,
                loadUrl,
                TvType.TvSeries,
                episodes
            ) {
                posterUrl = poster
                this.year = year
                this.tags = tags
                this.plot = plot
                if (status != null) showStatus = status
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = getPage(data, referer = mainUrl)

        val links = linkedSetOf<String>()

        document.select("div.player-embed iframe, .player-embed iframe")
            .forEach { iframe ->
                iframe.getIframeUrl()
                    ?.toAbsoluteUrl(data)
                    ?.takeIf { it.isNotBlank() }
                    ?.also { links.add(it) }
            }

        document.select("select.mirror option[value]")
            .forEach { option ->
                decodeMirror(
                    option.attr("value"),
                    data
                )?.also { links.add(it) }
            }

        document.select("div.dlbox a[href], .dlbox a[href]")
            .forEach { anchor ->
                anchor.attr("href")
                    .trim()
                    .takeIf { it.startsWith("http", true) }
                    ?.also { links.add(it) }
            }

        links.forEach { link ->
            runCatching {
                loadExtractor(
                    link,
                    data,
                    subtitleCallback,
                    callback
                )
            }.onFailure {
                println("OppaDrama extractor failed: ${it.message}")
            }
        }

        return links.isNotEmpty()
    }

    private suspend fun getPage(
        url: String,
        referer: String = mainUrl
    ): Document {
        return app.get(
            url,
            headers = headers,
            referer = referer,
            interceptor = webViewResolver
        ).document
    }

    private fun buildMainPageUrl(data: String, page: Int): String {
        if (data.isBlank()) {
            return if (page == 1) {
                mainUrl
            } else {
                "$mainUrl/page/$page/"
            }
        }

        val cleanData = data.trimStart('/')

        if (page == 1) {
            return "$mainUrl/$cleanData"
        }

        val separator = if (cleanData.contains("?")) "&" else "?"
        return "$mainUrl/$cleanData${separator}paged=$page"
    }

    private fun Document.selectCards(): List<Element> {
        val primary = select(
            "div.listupd article.bs, div.listupd article.stylefor, " +
                ".listupd article.bs, .listupd article.stylefor"
        )

        if (primary.isNotEmpty()) return primary

        return select("div.listupd div.bsx, .listupd .bsx")
    }

    private fun decodeMirror(value: String, referer: String): String? {
        val cleaned = value.trim()
        if (cleaned.isBlank()) return null

        if (
            cleaned.startsWith("http", true) ||
            cleaned.startsWith("//") ||
            cleaned.startsWith("/")
        ) {
            return cleaned.toAbsoluteUrl(referer)
        }

        return runCatching {
            val html = base64Decode(cleaned.replace(Regex("\\s"), ""))
            Jsoup.parse(html)
                .selectFirst("iframe")
                ?.getIframeUrl()
                ?.toAbsoluteUrl(referer)
        }.getOrNull()
    }

    private fun Document.findSeriesUrl(): String? {
        return select("a[href]")
            .firstOrNull { element ->
                val href = element.attr("href")
                href.startsWith(mainUrl) &&
                    !href.contains("-episode-", true) &&
                    !href.contains("/movie-", true) &&
                    element.text().isNotBlank()
            }
            ?.attr("href")
            ?.let { fixUrl(it) }
    }

    private fun Document.episodes(): List<Episode> {
        return select("div.eplister li a[href], .eplister li a[href]")
            .reversed()
            .mapIndexed { index, element ->
                val epNum = element
                    .selectFirst(".epl-num")
                    ?.text()
                    ?.safeInt()
                    ?: index + 1

                newEpisode(
                    fixUrl(element.attr("href"))
                ) {
                    episode = epNum
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
            ?.let { fixUrl(it) }
    }

    private fun Document.titleText(): String? {
        return selectFirst("h1.entry-title, h1")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun Document.descriptionText(): String? {
        return select(".entry-content p")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .takeIf { it.isNotBlank() }
    }

    private fun Document.tagsList(): List<String> {
        return select(".genxed a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
    }

    private fun Document.getInfo(key: String): String? {
        return select("div.spe span, .spe span")
            .firstOrNull { element ->
                element.text().startsWith("$key:", true)
            }
            ?.text()
            ?.substringAfter(":")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun Element.getImageUrl(): String? {
        return when {
            attr("data-src").isNotBlank() -> attr("data-src")
            attr("data-lazy-src").isNotBlank() -> attr("data-lazy-src")
            attr("data-original").isNotBlank() -> attr("data-original")
            attr("src").isNotBlank() -> attr("src")
            attr("srcset").isNotBlank() -> attr("srcset")
                .substringBefore(",")
                .substringBefore(" ")
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
                value.startsWith("http://", true) -> value
                value.startsWith("https://", true) -> value
                value.startsWith("//") -> "https:$value"
                value.startsWith("/") -> "${mainUrl.trimEnd('/')}$value"
                else -> URI(referer).resolve(value).toString()
            }
        }.getOrNull()
    }

    private fun String.safeInt(): Int? {
        return Regex("\\d+")
            .find(this)
            ?.value
            ?.toIntOrNull()
    }

    private fun parseYear(text: String?): Int? {
        return Regex("(19|20)\\d{2}")
            .find(text ?: "")
            ?.value
            ?.toIntOrNull()
    }

    private fun parseStatus(text: String?): ShowStatus? {
        val value = text?.lowercase() ?: return null

        return when {
            value.contains("completed") -> ShowStatus.Completed
            value.contains("ongoing") -> ShowStatus.Ongoing
            else -> null
        }
    }
}
