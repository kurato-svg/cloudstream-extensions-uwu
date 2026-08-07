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

class OppadramaProvider : MainAPI() {

    override var mainUrl = "http://45.11.57.192"

override var name = "OppaDrama"

override var lang = "id"

override val hasMainPage = true

override val supportedTypes = setOf(
    TvType.Movie,
    TvType.AsianDrama
)

private val headers = mapOf(
    "User-Agent" to
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/139.0 Safari/537.36",

    "Referer" to "$mainUrl/",

    "Origin" to mainUrl
)

    override val mainPage = mainPageOf(

    "" to "Latest Update",

    "series/?status=&type=Drama&order=update" to "Drama",

    "series/?type=Movie&order=update" to "Movie",

    "series/?country[]=south-korea&type=Drama&order=update" to "Korea",

    "series/?country[]=china&type=Drama&order=update" to "China",

    "series/?country[]=japan&type=Drama&order=update" to "Japan",

    "series/?country[]=thailand&type=Drama&order=update" to "Thailand"
)
    override suspend fun getMainPage(
    page: Int,
    request: MainPageRequest
): HomePageResponse {

    val url = if (request.data.isBlank()) {
        "$mainUrl/page/$page/"
    } else {
        val sep = if (request.data.contains("?")) "&" else "?"
        "$mainUrl/${request.data}$sep$page=$page"
    }

    val document = app.get(
        url,
        headers = headers
    ).document

    val items = document
        .select("div.listupd article.bs")
        .mapNotNull {
            it.toSearchResult()
        }

    val hasNext =
        document.selectFirst("div.hpage a.r") != null

    return newHomePageResponse(
        HomePageList(
            request.name,
            items
        ),
        hasNext
    )
    }

    private fun Element.toSearchResult(): SearchResponse? {

    val a = selectFirst("a") ?: return null

    val title =

        a.attr("title")
            .ifBlank {
                selectFirst(".tt")?.ownText()
            }
            ?.trim()
            ?: return null

    val href =
        fixUrl(a.attr("href"))

    val poster =

        selectFirst("img")
            ?.attr("src")
            ?.let(::fixUrl)

    val type =

        when {

            href.contains("/movie-", true) ->
                TvType.Movie

            else ->
                TvType.AsianDrama
        }

    return if (type == TvType.Movie) {

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
)
        {

            posterUrl = poster
        }
    }
    }

    override suspend fun search(
    query: String
): List<SearchResponse> {

    val document = app.get(

        "$mainUrl/?s=${query.replace(" ", "+")}",

        headers = headers

    ).document

    return document
        .select("div.listupd article.bs")
        .mapNotNull {

            it.toSearchResult()
        }
}
    override suspend fun load(url: String): LoadResponse {

    val document = app.get(
        url,
        headers = headers
    ).document

    val title = document
        .selectFirst("h1.entry-title")
        ?.text()
        ?.trim()
        ?: throw ErrorLoadingException("Title not found")

    val poster = document
        .selectFirst(".thumb img,.bigcontent img,.poster img")
        ?.attr("src")
        ?.let(::fixUrl)

    val description = document
        .selectFirst(".entry-content p")
        ?.text()

    val tags = document
        .select(".genxed a")
        .map { it.text() }

    val year = document
        .selectFirst(".spe span:contains(Tahun)")
        ?.text()
        ?.filter(Char::isDigit)
        ?.toIntOrNull()

    val status = when {

        document.text().contains(
            "Completed",
            true
        ) -> ShowStatus.Completed

        else -> ShowStatus.Ongoing
    }

    val episodes = document
        .select("div.eplister li a")
        .reversed()
        .mapIndexed { index, element ->

            val epUrl =
                fixUrl(element.attr("href"))

            val epName =
                element.selectFirst(".epl-title")
                    ?.text()
                    ?.trim()

            val epNum =
                element.selectFirst(".epl-num")
                    ?.text()
                    ?.filter(Char::isDigit)
                    ?.toIntOrNull()
                    ?: index + 1

            Episode(
                epUrl
            ).apply {

                name = epName

                episode = epNum
            }
        }

    val recommendations =
        document
            .select(".listupd article.bs")
            .mapNotNull {
                it.toSearchResult()
            }

    return 
        newTvSeriesLoadResponse(
    title,
    url,
    TvType.TvSeries,
            {

        posterUrl = poster

        plot = description

        this.year = year

        this.tags = tags

        showStatus = status

        this.recommendations =
            recommendations
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
