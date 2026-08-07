package com.oppadrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

class OppadramaProvider : MainAPI() {

    override var mainUrl = "http://45.11.57.192"

    override var name = "OppaDrama"

    override var lang = "id"

    override val hasMainPage = true

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
        val url = when {
            request.data.isBlank() -> {
                if (page == 1) mainUrl else "$mainUrl/page/$page/"
            }
            else -> {
                val separator = if (request.data.contains("?")) "&" else "?"
                "$mainUrl/${request.data}${separator}page=$page"
            }
        }

        val document = app.get(url, headers = headers).document

        val items = document
            .select("div.listupd > div.excstf > article.bs")
            .mapNotNull { it.toSearchResult() }

        val hasNext = document.selectFirst("div.hpage a.r") != null

        return newHomePageResponse(
            HomePageList(request.name, items),
            hasNext
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a[href]") ?: return null
        val href = fixUrl(anchor.attr("href"))

        val title = anchor.attr("title")
            .ifBlank { selectFirst(".tt")?.ownText() }
            .ifBlank { selectFirst("h2")?.text() }
            ?.trim() ?: return null

        if (title.isBlank()) return null

        val poster = selectFirst("img")?.getImageUrl()?.let(::fixUrl)
        val badge = selectFirst(".typez")?.text()?.lowercase() ?: ""

        return if (badge.contains("movie") || href.contains("/movie-", true)) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get(
            "$mainUrl/?s=${query.replace(" ", "+")}",
            headers = headers
        ).document

        return document
            .select("div.listupd > div.excstf > article.bs")
            .mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document

        val title = document.titleText() ?: throw ErrorLoadingException("Title not found")
        val poster = document.poster()
        val plot = document.descriptionText()
        val tags = document.tagsList()
        val year = parseYear(document.getInfo("Tahun") ?: document.getInfo("Year"))
        val status = parseStatus(document.getInfo("Status"))

        val isMovie = url.contains("/movie-", true)

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.tags = tags
                this.plot = plot
                this.recommendations = document.recommendations()
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, document.episodes()) {
                this.posterUrl = poster
                this.year = year
                this.tags = tags
                this.plot = plot
                this.showStatus = status
                this.recommendations = document.recommendations()
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(
            data,
            headers = headers,
            referer = mainUrl
        ).document

        val links = linkedSetOf<String>()

        // Main player
        document.select("div.player-embed iframe").forEach { iframe ->
            iframe.getIframeUrl()
                ?.takeIf { it.isNotBlank() }
                ?.let(::fixUrl)
                ?.let(links::add)
        }

        // Mirror selector
        document.select("select.mirror option[value]").forEach { option ->
            decodeMirror(option.attr("value"), data)?.let(links::add)
        }

        // Download fallback
        document.select("div.dlbox a[href]").forEach {
            it.attr("href")
                .takeIf { link -> link.startsWith("http") }
                ?.let(links::add)
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
                println(it)
            }
        }

        return links.isNotEmpty()
    }

    private fun decodeMirror(
        value: String,
        referer: String
    ): String? {

        if (value.isBlank()) return null

        if (value.startsWith("http") || value.startsWith("//")) {
            return fixUrl(value)
        }

        return runCatching {
            val html = base64Decode(value)
            Jsoup.parse(html)
                .selectFirst("iframe")
                ?.getIframeUrl()
                ?.let {
                    if (it.startsWith("//")) "https:$it" else fixUrl(it)
                }
        }.getOrNull()
    }
}

// ==========================================
// Extension / Helper Functions
// ==========================================

private fun Element.getImageUrl(): String? {
    return when {
        attr("data-src").isNotBlank() -> attr("data-src")
        attr("data-lazy-src").isNotBlank() -> attr("data-lazy-src")
        attr("data-original").isNotBlank() -> attr("data-original")
        attr("src").isNotBlank() -> attr("src")
        attr("srcset").isNotBlank() -> attr("srcset").substringBefore(",").substringBefore(" ")
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

private fun Document.getInfo(key: String): String? {
    return select("div.spe span")
        .firstOrNull { it.text().startsWith("$key:", true) }
        ?.text()
        ?.substringAfter(":")
        ?.trim()
}

private fun parseYear(text: String?): Int? {
    return Regex("(19|20)\\d{2}").find(text ?: "")?.value?.toIntOrNull()
}

private fun parseStatus(text: String?): ShowStatus? {
    val value = text?.lowercase() ?: return null
    return when {
        value.contains("completed") -> ShowStatus.Completed
        value.contains("ongoing") -> ShowStatus.Ongoing
        else -> null
    }
}

private fun Element.poster(): String? {
    return getImageUrl()?.let(::fixUrl)
}

private fun String.safeInt(): Int? {
    return Regex("\\d+").find(this)?.value?.toIntOrNull()
}

private fun Document.recommendations(): List<SearchResponse> {
    return select("div.listupd article.bs").mapNotNull {
        it.toSearchResultPrivate()
    }
}

private fun Element.toSearchResultPrivate(): SearchResponse? {
    val anchor = selectFirst("a[href]") ?: return null
    val href = fixUrl(anchor.attr("href"))

    val title = anchor.attr("title")
        .ifBlank { selectFirst(".tt")?.ownText() }
        .ifBlank { selectFirst("h2")?.text() }
        ?.trim() ?: return null

    if (title.isBlank()) return null
    val poster = selectFirst("img")?.getImageUrl()?.let(::fixUrl)

    return newMovieSearchResponse(title, href, TvType.Movie) {
        this.posterUrl = poster
    }
}

private fun Document.episodes(): List<Episode> {
    return select("div.eplister li a")
        .reversed()
        .mapIndexed { index, element ->
            val ep = element
                .selectFirst(".epl-num")
                ?.text()
                ?.safeInt()
                ?: (index + 1)

            newEpisode(fixUrl(element.attr("href"))) {
                episode = ep
                name = element.selectFirst(".epl-title")?.text()?.trim()
            }
        }
}

private fun Document.poster(): String? {
    return selectFirst(".thumb img, .poster img, .bigcontent img")?.poster()
}

private fun Document.titleText(): String? {
    return selectFirst("h1.entry-title, h1")?.text()?.trim()
}

private fun Document.descriptionText(): String? {
    return select(".entry-content p")
        .joinToString("\n") { it.text().trim() }
        .ifBlank { null }
}

private fun Document.tagsList(): List<String> {
    return select(".genxed a").map { it.text().trim() }
}
