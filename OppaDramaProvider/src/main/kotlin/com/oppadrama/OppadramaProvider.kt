package com.oppadrama

import com.lagradost.cloudstream3.*  
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors  
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore  
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer  
import com.lagradost.cloudstream3.base64Decode 
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.Jsoup

class OppadramaProvider : MainAPI() {
    override var mainUrl = "http://45.11.57.192" // Pastikan IP/URL ini yang paling terkini
    override var name = "OppaDrama"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    companion object {
        fun getStatus(t: String): ShowStatus {
            return when {
                t.contains("Completed", ignoreCase = true) -> ShowStatus.Completed
                t.contains("Ongoing", ignoreCase = true) -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "series/?status=&type=&order=update" to "Latest Update",
        "series/?country[]=china&type=Drama&order=update" to "Drama Chinese",
        "series/?country[]=japan&type=Drama&order=update" to "Drama Jepang",
        "series/?country[]=south-korea&status=&type=Drama&order=update" to "Drama Korea",
        "series/?country[]=philippines&type=Drama&order=update" to "Drama Philippines",
        "series/?country[]=taiwan&type=Drama&order=update" to "Drama Taiwan",
        "series/?country[]=thailand&type=Drama&order=update" to "Drama Thailand",
        "series/?country[]=usa&type=Drama&order=update" to "Drama Western",
        "series/?country[]=china&status=&type=Movie&order=update" to "Chinese Movie",
        "series/?country[]=hong-kong&status=&type=Movie&order=update" to "Hong Kong Movie",
        "series/?country[]=india&status=&type=Movie&order=update" to "India Movie",
        "series/?country[]=japan&type=Movie&order=update" to "Japan Movie",
        "series/?country[]=south-korea&status=&type=Movie&order=update" to "Korean Movie",
        "series/?country[]=philippines&status=&type=Movie&order=update" to "Philippines Movie",
        "series/?country[]=taiwan&status=&type=Movie&order=update" to "Taiwan Movie",
        "series/?country[]=thailand&status=&type=Movie&order=update" to "Thailand Movie",
        "series/?country[]=united-states&status=&type=Movie&order=update" to "Western Movie"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Pembaikan Struktur Pagination untuk Tema Wp-Manga / Themesia
        val pageUrl = if (page == 1) {
            "$mainUrl/${request.data}"
        } else {
            if (request.data.contains("?")) {
                val parts = request.data.split("?", limit = 2)
                "$mainUrl/${parts[0]}page/$page/?${parts.getOrNull(1) ?: ""}"
            } else {
                "$mainUrl/${request.data.removeSuffix("/")}/page/$page/"
            }
        }

        val document = app.get(pageUrl).document
        
        // CSS Selector Diluaskan untuk menangkap pelbagai bentuk grid
        val items = document.select("div.listupd article, div.listupd div.bs, div.post-item, div.item, article.item, div.box-item")
                            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(HomePageList(request.name, items), hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a") ?: return null
        val href = fixUrl(linkElement.attr("href"))
        
        // Pembaikan tangkapan Tajuk (Title) - lebih fleksibel
        var title = linkElement.attr("title")
        if (title.isNullOrBlank()) {
            title = this.selectFirst("div.tt, div.title, h2, h3, .ep_title")?.text() ?: ""
        }
        if (title.isBlank()) title = linkElement.text()
        if (title.isBlank()) return null // Gugurkan jika masih tiada tajuk

        val poster = this.selectFirst("img")?.getImageAttr()?.let { fixUrlNull(it) }
        
        val typeText = this.selectFirst("div.typez, span.type, .status")?.text()?.lowercase() ?: ""
        val isMovie = typeText.contains("movie") || href.contains("/movie/", true)

        return if (isMovie) {
            newMovieSearchResponse(title.trim(), href, TvType.Movie) {
                this.posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(title.trim(), href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.listupd article, div.listupd div.bs, div.post-item, div.item, article.item")
            .mapNotNull { it.toSearchResult() }
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val linkElement = this.selectFirst("a") ?: return null
        val href = fixUrl(linkElement.attr("href"))
        
        var title = this.selectFirst("div.tt, div.title")?.text()
        if (title.isNullOrBlank()) title = linkElement.attr("title")
        if (title.isNullOrBlank()) return null

        val posterUrl = this.selectFirst("img")?.getImageAttr()?.let { fixUrlNull(it) }
        
        return newMovieSearchResponse(title.trim(), href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title, h1.tit, h1[itemprop=name]")?.text()?.trim().orEmpty()
        val poster = document.selectFirst("div.bigcontent img, div.thumb img, div.poster img")?.getImageAttr()?.let { fixUrlNull(it) }
        
        val description = document.select("div.entry-content p, div.desc p, div.mindes p")
            .joinToString("\n") { it.text() }
            .trim()

        val year = document.selectFirst("span:matchesOwn((?i)Dirilis|Released:)")?.ownText()
            ?.filter { it.isDigit() }?.take(4)?.toIntOrNull()

        val duration = document.selectFirst("span:contains(Durasi:), span:contains(Duration:)")?.ownText()?.let {
            val h = Regex("(\\d+)\\s*hr").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val m = Regex("(\\d+)\\s*min").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            if (h == 0 && m == 0) it.filter { c -> c.isDigit() }.toIntOrNull() else h * 60 + m
        }

        val tags = document.select("div.genxed a, div.genre a, .sgen a").map { it.text() }

        val actors = document.select("span:has(b:matchesOwn((?i)Artis|Cast:)) a, div.cast a, .actor a")
            .map { it.text().trim() }

        val rating = document.selectFirst("div.rating strong, span[itemprop=ratingValue], .numrating")
            ?.text()
            ?.replace("Rating", "")
            ?.trim()
            ?.toDoubleOrNull()

        val trailer = document.selectFirst("div.bixbox.trailer iframe, div.embed-trailer iframe")?.attr("src")

        val statusText = document.selectFirst("div.info-content div.spe span, div.spe span:contains(Status)")
            ?.ownText()
            ?.replace(":", "")
            ?.trim()
            ?: ""
        val status = getStatus(statusText)

        val recommendations = document.select("div.listupd article, div.listupd div.bs, div.post-item")
            .mapNotNull { it.toRecommendResult() }

        val episodeElements = document.select("div.eplister ul li a, div.episodelist ul li a")

        val episodes = episodeElements
            .reversed()
            .mapIndexed { index, aTag ->
                val href = fixUrl(aTag.attr("href"))
                val epName = aTag.selectFirst("div.epl-num, .epl-title")?.text() ?: "Episode ${index + 1}"

                newEpisode(href) {
                    this.name = epName
                    this.episode = index + 1
                }
            }

        return if (episodes.size > 1) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.showStatus = status
                this.recommendations = recommendations
                this.duration = duration ?: 0
                if (rating != null) addScore(rating.toString(), 10)
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, episodes.firstOrNull()?.data ?: url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
                this.duration = duration ?: 0
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
        val document = app.get(data).document

        document.selectFirst("div.player-embed iframe, div.embed-container iframe, div.megamenu iframe")
            ?.getIframeAttr()
            ?.let { iframe ->
                loadExtractor(httpsify(iframe), data, subtitleCallback, callback)
            }

        val mirrorOptions = document.select("select.mirror option[value]:not([disabled]), ul.mirrors li a")
        for (opt in mirrorOptions) {
            val base64 = opt.attr("value").ifBlank { opt.attr("data-url") }
            if (base64.isBlank()) continue
            try {
                val cleaned = base64.replace("\\s".toRegex(), "")
                val decodedHtml = base64Decode(cleaned)
                val iframeTag = Jsoup.parse(decodedHtml).selectFirst("iframe")
                val mirrorUrl = when {
                    iframeTag?.attr("src")?.isNotBlank() == true -> iframeTag.attr("src")
                    iframeTag?.attr("data-src")?.isNotBlank() == true -> iframeTag.attr("data-src")
                    else -> decodedHtml // In case it's a direct link decoded
                }
                if (!mirrorUrl.isNullOrBlank()) {
                    loadExtractor(httpsify(mirrorUrl), data, subtitleCallback, callback)
                }
            } catch (_: Exception) {
                // Abaikan jika tak berjaya decode
            }
        }

        val downloadLinks = document.select("div.dlbox li span.e a[href], div.moredlbox a[href]")
        for (a in downloadLinks) {
            val url = a.attr("href").trim()
            if (url.isNotBlank()) {
                loadExtractor(httpsify(url), data, subtitleCallback, callback)
            }
        }

        return true
    }

    private fun Element.getImageAttr(): String {
        return when {
            this.hasAttr("data-src") -> this.attr("abs:data-src")
            this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
            this.hasAttr("srcset") -> this.attr("abs:srcset").substringBefore(" ")
            else -> this.attr("abs:src")
        }
    }

    private fun Element?.getIframeAttr(): String? {
        return this?.attr("data-litespeed-src").takeIf { !it.isNullOrEmpty() }
            ?: this?.attr("src")
    }
}
