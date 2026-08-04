package com.oppadrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class OppadramaProvider : MainAPI() {
    override var mainUrl = "http://45.11.57.192"
    override var name = "OppaDrama"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    private val customHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "" to "Latest Update",
        "series/?country[]=china&order=update" to "Drama Chinese",
        "series/?country[]=japan&order=update" to "Drama Jepang",
        "series/?country[]=south-korea&order=update" to "Drama Korea",
        "series/?country[]=taiwan&order=update" to "Drama Taiwan",
        "series/?country[]=thailand&order=update" to "Drama Thailand",
        "series/?type=Movie&order=update" to "Movies All",
        "series/?country[]=china&type=Movie" to "Chinese Movie",
        "series/?country[]=south-korea&type=Movie" to "Korean Movie"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (request.data.isEmpty()) {
            if (page <= 1) mainUrl else "$mainUrl/page/$page/"
        } else {
            val cleanData = request.data.removePrefix("/")
            if (page <= 1) {
                "$mainUrl/$cleanData"
            } else {
                val separator = if (cleanData.contains("?")) "&" else "?"
                "$mainUrl/$cleanData${separator}page=$page"
            }
        }

        return try {
            val document = app.get(pageUrl, headers = customHeaders).document
            
            // Mengambil terus tag <article class="bs"> dari HTML OppaDrama
            val items = document.select("article.bs")
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }

            newHomePageResponse(HomePageList(request.name, items), hasNext = items.isNotEmpty())
        } catch (e: Exception) {
            newHomePageResponse(HomePageList(request.name, emptyList()), hasNext = false)
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = this.selectFirst("a") ?: return null
        val href = fixUrl(aTag.attr("href"))
        
        // Mengambil tajuk dari h2 itemprop="headline", div.tt atau attribute title
        val title = (this.selectFirst("h2[itemprop=headline]")?.text()
            ?: this.selectFirst("div.tt")?.ownText()
            ?: aTag.attr("title")).trim()

        if (title.isBlank()) return null

        // Mengambil gambar poster terus dari atribut src atau data-src
        val img = this.selectFirst("img")
        val posterUrl = img?.attr("abs:src")?.ifBlank { null }
            ?: img?.attr("abs:data-src")?.ifBlank { null }

        val typeStr = this.selectFirst("div.typez")?.text()?.lowercase() ?: ""
        val isMovie = typeStr.contains("movie") || href.contains("/movie/", true)

        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url, headers = customHeaders).document
        return document.select("article.bs")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = customHeaders).document

        val title = document.selectFirst("h1.entry-title, h1[itemprop=headline]")?.text()?.trim().orEmpty()
        val poster = document.selectFirst("div.bigcontent img, div.thumb img")?.let { img ->
            img.attr("abs:src").ifBlank { null } ?: img.attr("abs:data-src")
        }
        val plot = document.select("div.entry-content p, div.desc p").joinToString("\n") { it.text() }.trim()

        val year = document.selectFirst("span:matchesOwn((?i)Dirilis|Released)")?.ownText()
            ?.filter { it.isDigit() }?.take(4)?.toIntOrNull()

        val tags = document.select("div.genxed a, div.genre a").map { it.text() }
        val actors = document.select("span:has(b:matchesOwn((?i)Artis|Cast)) a").map { it.text().trim() }

        val episodeElements = document.select("div.eplister ul li a")
        val episodes = episodeElements.reversed().mapIndexed { index, aTag ->
            val epHref = fixUrl(aTag.attr("href"))
            val epName = aTag.selectFirst("div.epl-num")?.text() ?: "Episode ${index + 1}"
            newEpisode(epHref) {
                this.name = epName
                this.episode = index + 1
            }
        }

        return if (episodes.size > 1) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                addActors(actors)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, episodes.firstOrNull()?.data ?: url) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                addActors(actors)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = customHeaders).document

        document.selectFirst("div.player-embed iframe, div.embed-container iframe")?.attr("src")?.let { iframe ->
            loadExtractor(httpsify(iframe), data, subtitleCallback, callback)
        }

        return true
    }
}
