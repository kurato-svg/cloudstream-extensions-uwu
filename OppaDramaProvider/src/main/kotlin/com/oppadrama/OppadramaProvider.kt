package com.oppadrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

import org.jsoup.nodes.Element

class OppadramaProvider : MainAPI() {

    override var mainUrl = "http://45.11.57.192"
    override var name = "OppaDrama"

    override val hasMainPage = true
    override var lang = "id"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
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

    override suspend fun getMainPage(
    page: Int,
    request: MainPageRequest
): HomePageResponse {

    val url = "http://45.11.57.192/series/?status=&type=&order=update&page=$page"

    println("OPPADRAMA TEST URL = [$url]")

    val document = app.get(url).document

    println("OPPADRAMA V2 TITLE = ${document.title()}")

    // baki code...
        val items = document
            .select("article, .item, .bs, .listupd > div")
            .mapNotNull { it.toSearchResult() }

        println("OPPADRAMA V2 ITEMS = ${items.size}")

        return newHomePageResponse(
            HomePageList(
                request.name,
                items,
                isHorizontalImages = false
            ),
            hasNext = items.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {

        val link = selectFirst("a[href]") ?: return null

        val href = link.attr("href").trim()

        if (href.isBlank()) return null

        val title = when {
            link.attr("title").isNotBlank() ->
                link.attr("title").trim()

            selectFirst(".title, .tt, .name") != null ->
                selectFirst(".title, .tt, .name")!!.text().trim()

            else ->
                link.text().trim()
        }

        if (title.isBlank()) return null

        val poster = selectFirst("img")?.let { img ->

            when {
                img.attr("data-src").isNotBlank() ->
                    fixUrlNull(img.attr("data-src"))

                img.attr("data-lazy-src").isNotBlank() ->
                    fixUrlNull(img.attr("data-lazy-src"))

                img.attr("src").isNotBlank() ->
                    fixUrlNull(img.attr("src"))

                else -> null
            }
        }

        val fixedUrl = fixUrl(href)

        return newTvSeriesSearchResponse(
            title,
            fixedUrl,
            TvType.TvSeries
        ) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val url = "$mainUrl/?s=${query.replace(" ", "+")}"

        println("OPPADRAMA V2 SEARCH = $url")

        val document = app.get(url).document

        return document
            .select("article, .item, .bs, .listupd > div")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {

        val document = app.get(fixUrl(url)).document

        val title = document
            .selectFirst("h1.entry-title, h1")
            ?.text()
            ?.trim()
            ?: "Unknown"

        val poster = document
            .selectFirst(
                "meta[property=og:image]"
            )
            ?.attr("content")
            ?.let { fixUrlNull(it) }

        val description = document
            .selectFirst(
                ".entry-content, .desc, .description"
            )
            ?.text()
            ?.trim()

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            emptyList()
        ) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return false
    }
}
