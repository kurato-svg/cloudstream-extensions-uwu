package com.AnichinV2

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class AnichinV2 : MainAPI() {

    override var mainUrl = "https://anichin.moe"  
    override var name = "Anichin V2"  
    override val hasMainPage = true  
    override var lang = "id"  
    override val hasDownloadSupport = true  

    override val supportedTypes = setOf(  
        TvType.Movie,  
        TvType.Anime  
    )  

    // Header khas untuk memastikan pelayan imej Anichin tidak menyekat paparan gambar (Hotlink Protection)
    override fun getImageHeaders(): Map<String, String> {
        return mapOf(
            "Referer" to "$mainUrl/",
            "User-Agent" to USER_AGENT
        )
    }

    override val mainPage = mainPageOf(  
        "anime/?order=update" to "Latest Update",  
        "anime/?status=ongoing&order=update" to "Series Ongoing",  
        "anime/?status=completed&order=update" to "Series Completed",  
        "anime/?status=hiatus&order=update" to "Series Drop/Hiatus",  
        "anime/?type=movie&order=update" to "Movie"  
    )  

    override suspend fun getMainPage(  
        page: Int,  
        request: MainPageRequest  
    ): HomePageResponse {  

        val document = app.get(  
            "${mainUrl}/${request.data}&page=$page"  
        ).document  

        val home = document  
            .select("div.listupd > article")  
            .mapNotNull { it.toSearchResult() }  

        return newHomePageResponse(  
            list = HomePageList(  
                name = request.name,  
                list = home,  
                isHorizontalImages = false  
            ),  
            hasNext = true  
        )  
    }  

    private fun Element.toSearchResult(): SearchResponse {  

        val title = this  
            .select("div.bsx > a")  
            .attr("title")  
            .trim()  

        val href = fixUrl(  
            this.select("div.bsx > a")  
                .attr("href")  
        )  

        val imgElem = this.selectFirst("div.bsx > a img")
        val rawPoster = imgElem?.attr("data-src")?.ifBlank { null }
            ?: imgElem?.attr("data-lazy-src")?.ifBlank { null }
            ?: imgElem?.attr("src")?.ifBlank { null }

        val posterUrl = fixUrlNull(rawPoster)  

        return newAnimeSearchResponse(  
            title,  
            href,  
            TvType.Anime  
        ) {  
            this.posterUrl = posterUrl  
        }  
    }  

    override suspend fun search(  
        query: String  
    ): List<SearchResponse> {  

        val searchResponse = mutableListOf<SearchResponse>()  

        for (i in 1..3) {  

            val document = app.get(  
                "${mainUrl}/page/$i/?s=$query"  
            ).document  

            val results = document  
                .select("div.listupd > article")  
                .mapNotNull { it.toSearchResult() }  

            if (results.isEmpty()) break  

            searchResponse.addAll(results)  
        }  

        return searchResponse.distinctBy {  
            it.url  
        }  
    }  

    override suspend fun load(  
        url: String  
    ): LoadResponse {  

        val document = app.get(  
            fixUrl(url)  
        ).document  

        val title = document  
            .selectFirst("h1.entry-title")  
            ?.text()  
            ?.trim()  
            .orEmpty()  

        // Carian poster berlapis (data-src, data-lazy-src, src, srcset, atau og:image)
        val posterImg = document.selectFirst("div.ime > img")  
            ?: document.selectFirst("div.thumb > img")
            
        val rawPoster = posterImg?.attr("data-src")?.ifBlank { null }  
            ?: posterImg?.attr("data-lazy-src")?.ifBlank { null }  
            ?: posterImg?.attr("src")?.ifBlank { null }  
            ?: posterImg?.attr("srcset")?.split(",")?.firstOrNull()?.trim()?.split(" ")?.firstOrNull()
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")  

        val poster = fixUrlNull(rawPoster)  

        val description = document  
            .selectFirst("div.entry-content")  
            ?.text()  
            ?.trim()  

        val type = document  
            .selectFirst(".spe")  
            ?.text()  
            .orEmpty()  

        val tvType = if (type.contains("Movie", true)) TvType.Movie else TvType.TvSeries  

        return if (tvType == TvType.TvSeries) {  

            val episodes = document  
                .select(".eplister li")  
                .map { ep ->  

                    val link = fixUrl(  
                        ep.selectFirst("a")  
                            ?.attr("href")  
                            .orEmpty()  
                    )  

                    val epTitle = ep  
                        .selectFirst(".epl-title")  
                        ?.text()  
                        ?.trim()  
                        .orEmpty()  

                    val epSub = ep  
                        .selectFirst(".epl-sub span")  
                        ?.text()  
                        ?.trim()  
                        .orEmpty()  

                    val epDate = ep  
                        .selectFirst(".epl-date")  
                        ?.text()  
                        ?.trim()  
                        .orEmpty()  

                    val cleanTitle = epTitle  
                        .replace(  
                            Regex(  
                                "Episode\\s*\\d+\\s*Subtitle Indonesia",  
                                RegexOption.IGNORE_CASE  
                            ),  
                            ""  
                        )  
                        .replace(  
                            "Subtitle Indonesia",  
                            ""  
                        )  
                        .trim()  

                    val name = "— $cleanTitle $epSub Indonesia".trim()  

                    val desc =  
                        if (epDate.isNotEmpty()) {  
                            "Rilis: $epDate"  
                        } else {  
                            null  
                        }  

                    newEpisode(link) {  
                        this.name = name  
                        this.posterUrl = poster  
                        this.description = desc  
                    }  
                }  
                .reversed()  

            newTvSeriesLoadResponse(  
                title,  
                url,  
                TvType.Anime,  
                episodes  
            ) {  
                this.posterUrl = poster  
                this.plot = description  
            }  

        } else {  

            val movieHref =  
                document  
                    .selectFirst(".eplister li > a")  
                    ?.attr("href")  
                    ?.let { fixUrl(it) }  
                    ?: url  

            newMovieLoadResponse(  
                title,  
                movieHref,  
                TvType.Movie,  
                movieHref  
            ) {  
                this.posterUrl = poster  
                this.plot = description  
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
            fixUrl(data)  
        ).document  

        document.select(".mobius option").forEach { server ->  

            val serverName = server.text().trim()  
            val base64 = server.attr("value")  

            if (base64.isBlank()) return@forEach  

            try {  

                val decoded = base64Decode(base64)  
                val doc = Jsoup.parse(decoded)  

                val iframe = doc  
                    .selectFirst("iframe")  
                    ?.attr("src")  
                    ?.trim()  
                    .orEmpty()  

                if (iframe.isBlank()) return@forEach  

                val streamUrl = fixUrl(iframe)  

                val streamResponse = app.get(  
                    streamUrl,  
                    headers = mapOf(  
                        "Referer" to fixUrl(data),  
                        "Origin" to mainUrl,  
                        "User-Agent" to USER_AGENT  
                    )  
                )  

                val playerUrl = streamResponse.document  
                    .selectFirst("iframe[src]")  
                    ?.attr("src")  
                    ?.trim()  
                    .orEmpty()  

                if (playerUrl.isBlank()) return@forEach  

                val fixedPlayerUrl = fixUrl(playerUrl)  

                try {  

                    val playerResponse = app.get(  
                        fixedPlayerUrl,  
                        headers = mapOf(  
                            "Referer" to streamUrl,  
                            "User-Agent" to USER_AGENT  
                        )  
                    )  

                    val realEmbedUrl = playerResponse.document  
                        .selectFirst("iframe[src]")  
                        ?.attr("src")  
                        ?.trim()  
                        .orEmpty()  

                    if (realEmbedUrl.isNotBlank()) {  

                        val fixedEmbedUrl = fixUrl(realEmbedUrl)  

                        loadExtractor(  
                            fixedEmbedUrl,  
                            fixedPlayerUrl,  
                            subtitleCallback,  
                            callback  
                        )  

                    } else {  

                        loadExtractor(  
                            fixedPlayerUrl,  
                            streamUrl,  
                            subtitleCallback,  
                            callback  
                        )  
                    }  

                } catch (e: Exception) {  

                    loadExtractor(  
                        fixedPlayerUrl,  
                        streamUrl,  
                        subtitleCallback,  
                        callback  
                    )  
                }  

            } catch (e: Exception) {  
                // Log error if needed
            }  
        }  

        return true  
    }  
}
