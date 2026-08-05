package com.oppadrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

import org.jsoup.nodes.Element

class OppadramaProvider : MainAPI() {

    override var mainUrl = "http://45.11.57.192"
    override var name = "OppaDrama"
    override var lang = "id"

    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
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

        val url = "$mainUrl/${request.data}&page=$page"

        val document = app.get(url).document

println("OPPADRAMA DEBUG URL = [$url]")
println("OPPADRAMA DEBUG TITLE = [${document.title()}]")
println("OPPADRAMA DEBUG HTML = ${document.html().length}")
println("OPPADRAMA DEBUG BODY = [${document.body().text()}]")

val items = emptyList<SearchResponse>()

return newHomePageResponse(
    HomePageList(request.name, items),
    hasNext = false
)
    }

    private fun Element.toSearchResult(): SearchResponse? {

        val link = selectFirst("a[href]") ?: return null

        val href = fixUrl(link.attr("href"))

        if (href.isBlank()) return null

        val title = when {
            link.attr("title").isNotBlank() ->
                link.attr("title").trim()

            selectFirst(".tt") != null ->
                selectFirst(".tt")!!.text().trim()

            else ->
                link.text().trim()
        }

        if (title.isBlank()) return null

        val image = selectFirst("img")

        val poster = when {
            image?.attr("data-src")?.isNotBlank() == true ->
                fixUrlNull(image.attr("data-src"))

            image?.attr("data-lazy-src")?.isNotBlank() == true ->
                fixUrlNull(image.attr("data-lazy-src"))

            image?.attr("src")?.isNotBlank() == true ->
                fixUrlNull(image.attr("src"))

            else -> null
        }

        return newTvSeriesSearchResponse(
            title,
            href,
            TvType.TvSeries
        ) {
            posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {

        val url = "$mainUrl/?s=${query.replace(" ", "+")}"

        val document = app.get(url).document

        return document
            .select(".listupd .bs")
            .mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {

        val document = app.get(url).document

        val title = document
            .selectFirst("h1.entry-title")
            ?.text()
            ?.trim()
            ?: return newMovieLoadResponse(
                "Unknown",
                url,
                TvType.Movie,
                url
            )

        val poster = document
            .selectFirst(".thumb img, .poster img")
            ?.let {
                when {
                    it.attr("data-src").isNotBlank() ->
                        fixUrlNull(it.attr("data-src"))

                    it.attr("src").isNotBlank() ->
                        fixUrlNull(it.attr("src"))

                    else -> null
                }
            }

        return newMovieLoadResponse(
            title,
            url,
            TvType.Movie,
            url
        ) {
            posterUrl = poster
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
