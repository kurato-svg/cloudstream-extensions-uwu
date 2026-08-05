package com.anichin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class AnichinProvider : MainAPI() {
    override var mainUrl = "https://anichin.cafe"
    override var name = "Anichin"
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.Anime)
    override var lang = "id"
    override val hasMainPage = true

    private fun fixUrl(url: String?): String {
        if (url.isNullOrEmpty()) return ""
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        if (url.startsWith("//")) return "https:$url"
        if (url.startsWith("/")) return "$mainUrl$url"
        return "$mainUrl/$url"
    }

    // 1. HOME PAGE (Latest Donghua Releases)
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "$mainUrl/page/$page/" else mainUrl
        val document = app.get(url).document
        val items = mutableListOf<SearchResponse>()

        // Themesia cards use div.bsx inside listupd
        document.select("div.listupd div.bsx, div.post-show div.bsx").forEach { element ->
            val title = element.selectFirst("div.tt, h2, .title")?.text()?.trim() ?: ""
            val rawHref = element.selectFirst("a")?.attr("href") ?: ""
            
            // Themesia uses data-src or src for thumbnails
            val imgElement = element.selectFirst("img")
            val rawPoster = imgElement?.attr("data-src")?.ifEmpty { null }
                ?: imgElement?.attr("src") 
                ?: ""

            val epNum = element.selectFirst("span.epx, .bt .epx")?.text()?.trim()

            if (title.isNotEmpty() && rawHref.isNotEmpty()) {
                val animeSearch = newAnimeSearchResponse(title, fixUrl(rawHref), TvType.Anime) {
                    this.posterUrl = fixUrl(rawPoster)
                    if (!epNum.isNullOrEmpty()) {
                        this.subStatus = EnumSet.of(DubStatus.Subbed)
                    }
                }
                items.add(animeSearch)
            }
        }

        return newHomePageResponse(
            listOf(HomePageList("Latest Releases", items)),
            hasNext = true
        )
    }

    // 2. SEARCH
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document

        return document.select("div.listupd div.bsx, div.result-item").mapNotNull { element ->
            val title = element.selectFirst("div.tt, h2, .title")?.text()?.trim() ?: return@mapNotNull null
            val rawHref = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val imgElement = element.selectFirst("img")
            val rawPoster = imgElement?.attr("data-src")?.ifEmpty { null } 
                ?: imgElement?.attr("src") 
                ?: ""

            newAnimeSearchResponse(title, fixUrl(rawHref), TvType.Anime) {
                this.posterUrl = fixUrl(rawPoster)
            }
        }
    }

    // 3. LOAD DETAILS & EPISODES
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: "Unknown Title"
        val imgElement = document.selectFirst("div.thumb img, div.poster img")
        val poster = imgElement?.attr("data-src")?.ifEmpty { null } 
            ?: imgElement?.attr("src") 
            ?: ""

        val description = document.selectFirst("div.entry-content, div.desc, div.synopsis")?.text()?.trim()

        val episodeList = mutableListOf<Episode>()

        // Scraping the Episode List
        document.select("ul.eplister li, div.eplister ul li").forEach { li ->
            val epHref = li.selectFirst("a")?.attr("href") ?: ""
            val epTitle = li.selectFirst(".epl-title")?.text()?.trim() 
                ?: li.selectFirst(".epl-num")?.text()?.trim() 
                ?: "Episode"
            val epNumStr = li.selectFirst(".epl-num")?.text()?.replace(Regex("[^0-9.]"), "")

            if (epHref.isNotEmpty()) {
                episodeList.add(
                    newEpisode(fixUrl(epHref)) {
                        this.name = epTitle
                        this.episode = epNumStr?.toIntOrNull()
                    }
                )
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = fixUrl(poster)
            this.plot = description
            // Reverse so Episode 1 appears first
            this.episodes = episodeList.reversed()
        }
    }

    // 4. EXTRACT STREAMING LINKS
    override suspend fun loadLinks(
        data: String,
        isCstyle: Boolean,
        substring: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // Check player container for default iframe
        document.select("div#embed_holder iframe, div.player-embed iframe, iframe").forEach { iframe ->
            val iframeUrl = fixUrl(iframe.attr("src"))
            if (iframeUrl.isNotEmpty()) {
                // Pass directly to CloudStream's automatic link extractors
                loadExtractor(iframeUrl, subtitleCallback, callback)
            }
        }

        return true
    }
}
