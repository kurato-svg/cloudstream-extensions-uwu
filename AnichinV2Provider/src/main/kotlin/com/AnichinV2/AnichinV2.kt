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

    val posterUrl = fixUrlNull(  
        this.select("div.bsx > a img")  
            .attr("src")  
    )  

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
        .toString()  

    var poster = document  
        .select("div.ime > img")  
        .attr("src")  

    val description = document  
        .selectFirst("div.entry-content")  
        ?.text()  
        ?.trim()  

    val type = document  
        .selectFirst(".spe")  
        ?.text()  
        .orEmpty()  

    val tvType =  
        if (type.contains("Movie", true)) {  
            TvType.Movie  
        } else {  
            TvType.TvSeries  
        }  

    if (poster.isEmpty()) {  
        poster = document  
            .selectFirst("meta[property=og:image]")  
            ?.attr("content")  
            .orEmpty()  
    }  

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

                val name =  
                    "— $cleanTitle $epSub Indonesia"  
                        .trim()  

                val desc =  
                    if (epDate.isNotEmpty()) {  
                        "Rilis: $epDate"  
                    } else {  
                        null  
                    }  

                newEpisode(link) {  
                    this.name = name  
                    this.posterUrl = fixUrlNull(poster)  
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
            this.posterUrl = fixUrlNull(poster)  
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
            this.posterUrl = fixUrlNull(poster)  
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
         
        println("========== ANICHIN SERVER ==========")

println("SERVER: ${server.text().trim()}")
println("VALUE: ${server.attr("value")}")
println("====================================")

val serverName = server.text().trim()  
        val base64 = server.attr("value")  

        println("ANICHIN V2 SERVER NAME:")  
        println(serverName)  

        println("ANICHIN V2 BASE64:")  
        println(base64)  

        if (base64.isBlank()) return@forEach  

        try {  

            val decoded = base64Decode(base64)  
            val doc = Jsoup.parse(decoded)  

            val iframe = doc  
                .selectFirst("iframe")  
                ?.attr("src")  
                ?.trim()  
                .orEmpty()  

            println("ANICHIN V2 IFRAME:")  
            println(iframe)  

            if (iframe.isBlank()) return@forEach  

            val streamUrl = fixUrl(iframe)  

            println("ANICHIN V2 STREAM URL:")  
            println(streamUrl)  

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

            if (playerUrl.isBlank()) {  
                println("ANICHIN V2: NO PLAYER URL")  
                return@forEach  
            }  

            val fixedPlayerUrl = fixUrl(playerUrl)  

            println("ANICHIN V2 PLAYER URL:")  
            println(fixedPlayerUrl)  

            /*  
             * Try the player wrapper.  
             *  
             * Some servers return another iframe here,  
             * for example OK.ru / Dailymotion.  
             */  
             
try {

    loadExtractor(
        fixedPlayerUrl,
        streamUrl,
        subtitleCallback,
        callback
    )

} catch (_: Exception) {
}
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
} 
                else {  

                    /*  
                     * No second iframe.  
                     *  
                     * Some servers may already provide  
                     * an extractor-compatible player URL.  
                     */  

                    println("ANICHIN V2 NO SECOND IFRAME")  
                    println("ANICHIN V2 TRY ORIGINAL PLAYER")  

                    loadExtractor(  
                        fixedPlayerUrl,  
                        streamUrl,  
                        subtitleCallback,  
                        callback  
                    )  
                }  

            } catch (e: Exception) {  

                /*  
                 * If opening the player wrapper fails,  
                 * still try the original player URL.  
                 */  

                println(  
                    "ANICHIN V2 PLAYER ERROR [$serverName]: ${e.message}"  
                )  

                loadExtractor(  
                    fixedPlayerUrl,  
                    streamUrl,  
                    subtitleCallback,  
                    callback  
                )  
            }  

        } catch (e: Exception) {  

            println(  
                "ANICHIN V2 SERVER ERROR [$serverName]: ${e.message}"  
            )  
        }  
    }  

    return true  
}

}
