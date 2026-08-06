package com.AnichinV2

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI

class AnichinV2 : MainAPI() {

    override var mainUrl = "https://anichin.moe"
    override var name = "Anichin V2"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.Anime,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "anime/?order=update" to "Latest Update",
        "anime/?status=ongoing&order=update" to "Series Ongoing",
        "anime/?status=completed&order=update" to "Series Completed",
        "anime/?status=hiatus&order=update" to "Series Drop/Hiatus",
        "anime/?type=movie&order=update" to "Movie"
    )

    private val defaultHeaders
        get() = mapOf(
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl,
            "User-Agent" to USER_AGENT
        )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val separator =
            if (request.data.contains("?")) "&" else "?"

        val url =
            "$mainUrl/${request.data}${separator}page=$page"

        val document = app.get(
            url,
            headers = defaultHeaders
        ).document

        val home = document
            .select("div.listupd > article")
            .mapNotNull { element ->
                element.toSearchResult()
            }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {

        val anchor = selectFirst("div.bsx > a")
            ?: selectFirst("a")
            ?: return null

        val title = anchor
            .attr("title")
            .ifBlank {
                selectFirst(".tt")
                    ?.text()
                    .orEmpty()
            }
            .trim()

        val href = resolveUrl(
            anchor.attr("href"),
            mainUrl
        ) ?: return null

        if (title.isBlank()) {
            return null
        }

        val image = selectFirst(
            "div.bsx > a img, div.bsx img, img"
        )

        val poster = image
            ?.attr("data-src")
            ?.takeIf { it.isNotBlank() }
            ?: image
                ?.attr("data-lazy-src")
                ?.takeIf { it.isNotBlank() }
            ?: image
                ?.attr("src")
                ?.takeIf { it.isNotBlank() }

        return newAnimeSearchResponse(
            title,
            href,
            TvType.Anime
        ) {
            this.posterUrl = resolveUrl(
                poster.orEmpty(),
                mainUrl
            )
        }
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        if (query.isBlank()) {
            return emptyList()
        }

        val results = mutableListOf<SearchResponse>()

        for (page in 1..3) {

            val searchUrl =
                "$mainUrl/page/$page/?s=${query.trim()}"

            val document = app.get(
                searchUrl,
                headers = defaultHeaders
            ).document

            val pageResults = document
                .select("div.listupd > article")
                .mapNotNull { element ->
                    element.toSearchResult()
                }

            if (pageResults.isEmpty()) {
                break
            }

            results.addAll(pageResults)
        }

        return results.distinctBy { result ->
            result.url
        }
    }

    override suspend fun load(
        url: String
    ): LoadResponse {

        val fixedUrl = resolveUrl(url, mainUrl)
            ?: url

        val document = app.get(
            fixedUrl,
            headers = defaultHeaders
        ).document

        val title = document
            .selectFirst("h1.entry-title")
            ?.text()
            ?.trim()
            .orEmpty()
            .ifBlank {
                document
                    .selectFirst("meta[property=og:title]")
                    ?.attr("content")
                    ?.trim()
                    .orEmpty()
            }

        var poster = document
            .selectFirst("div.ime img")
            ?.let { image ->
                image.attr("data-src")
                    .ifBlank {
                        image.attr("data-lazy-src")
                    }
                    .ifBlank {
                        image.attr("src")
                    }
            }
            .orEmpty()

        if (poster.isBlank()) {
            poster = document
                .selectFirst("meta[property=og:image]")
                ?.attr("content")
                .orEmpty()
        }

        val fixedPoster = resolveUrl(
            poster,
            fixedUrl
        )

        val description = document
            .selectFirst(
                "div.entry-content, div.synp .entry-content, .desc"
            )
            ?.text()
            ?.trim()

        val information = document
            .selectFirst(".spe")
            ?.text()
            .orEmpty()

        val isMovie =
            information.contains("Movie", ignoreCase = true) ||
                document.select(".eplister li").size <= 1 &&
                document.select(
                    ".spe span, .info-content .spe span"
                ).any { element ->
                    element.text().contains(
                        "Movie",
                        ignoreCase = true
                    )
                }

        return if (!isMovie) {

            val rawEpisodes = document
                .select(".eplister li")
                .mapNotNull { element ->
