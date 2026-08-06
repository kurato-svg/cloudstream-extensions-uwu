package com.AnichinV2

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

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

        val pageUrl = if (request.data.contains("?")) {
            "$mainUrl/${request.data}&page=$page"
        } else {
            "$mainUrl/${request.data}?page=$page"
        }

        val document = app.get(pageUrl).document

        val home = document
            .select("div.listupd > article")
            .mapNotNull { it.toSearchResult() }

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

        val title = this
            .selectFirst("div.bsx > a")
            ?.attr("title")
            ?.trim()
            .orEmpty()

        if (title.isBlank()) return null

        val href = fixUrlNull(
            this.selectFirst("div.bsx > a")
                ?.attr("href")
                .orEmpty()
        ) ?: return null

        val posterUrl = fixUrlNull(
            this.selectFirst("div.bsx > a img")
                ?.attr("src")
                .orEmpty()
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

        val encodedQuery = URLEncoder.encode(
            query,
            "UTF-8"
        )

        for (page in 1..3) {

            val document = app.get(
                "$mainUrl/page/$page/?s=$encodedQuery"
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

        val fixedUrl = fixUrl(url)

        val document = app.get(fixedUrl).document

        val title = document
            .selectFirst("h1.entry-title")
            ?.text()
            ?.trim()
            .orEmpty()

        var poster = document
            .selectFirst("div.ime > img")
            ?.attr("src")
            ?.trim()
            .orEmpty()

        if (poster.isBlank()) {
            poster = document
                .selectFirst("meta[property=og:image]")
                ?.attr("content")
                ?.trim()
                .orEmpty()
        }

        val description = document
            .selectFirst("div.entry-content")
            ?.text()
            ?.trim()

        val infoText = document
            .selectFirst(".spe")
            ?.text()
            .orEmpty()

        val isMovie = infoText.contains(
            "Movie",
            ignoreCase = true
        )

        return if (!isMovie) {

            val episodeElements = document
                .select(".eplister li")
                .toList()
                .reversed()

            val episodes = episodeElements.mapIndexedNotNull { index, ep ->

                val link = fixUrlNull(
                    ep.selectFirst("a")
                        ?.attr("href")
                        .orEmpty()
                ) ?: return@mapIndexedNotNull null

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

                val detectedEpisodeNumber = Regex(
                    "Episode\\s*(\\d+)",
                    RegexOption.IGNORE_CASE
                ).find(epTitle)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()

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
                        "",
                        ignoreCase = true
                    )
                    .trim()

                val episodeName = when {
                    cleanTitle.isNotBlank() && epSub.isNotBlank() ->
                        "$cleanTitle $epSub Indonesia"

                    cleanTitle.isNotBlank() ->
                        cleanTitle

                    detectedEpisodeNumber != null ->
                        "Episode $detectedEpisodeNumber"

                    else ->
                        "Episode ${index + 1}"
                }

                val desc = if (epDate.isNotBlank()) {
                    "Rilis: $epDate"
                } else {
                    null
                }

                newEpisode(link) {
                    this.name = episodeName
                    this.episode = detectedEpisodeNumber ?: index + 1
                    this.posterUrl = fixUrlNull(poster)
                    this.description = desc
                }
            }

            newTvSeriesLoadResponse(
                title,
                fixedUrl,
                TvType.Anime,
                episodes
            ) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = description
            }

        } else {

            val movieHref = document
                .selectFirst(".eplister li > a")
                ?.attr("href")
                ?.let { fixUrl(it) }
                ?: fixedUrl

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

        val episodeUrl = fixUrl(data)

        val document = app.get(
            episodeUrl,
            headers = mapOf(
                "Referer" to mainUrl,
                "User-Agent" to USER_AGENT
            )
        ).document

        val processedUrls = mutableSetOf<String>()

        document.select(".mobius option").forEach { server ->

            val serverName = server.text().trim()
            val encodedValue = server.attr("value").trim()

            if (encodedValue.isBlank()) {
                return@forEach
            }

            try {

                val decodedHtml = base64Decode(encodedValue)
                val decodedDocument = Jsoup.parse(decodedHtml)

                val firstIframeUrls = decodedDocument
                    .select("iframe[src]")
                    .mapNotNull {
                        it.attr("src")
                            .trim()
                            .takeIf { src -> src.isNotBlank() }
                    }

                if (firstIframeUrls.isEmpty()) {
                    println("ANICHIN V2 NO FIRST IFRAME: $serverName")
                    return@forEach
                }

                firstIframeUrls.forEach { firstIframe ->

                    val streamUrl = resolveUrl(
                        episodeUrl,
                        firstIframe
                    )

                    println("========== ANICHIN V2 SERVER ==========")
                    println("SERVER: $serverName")
                    println("STREAM URL: $streamUrl")
                    println("=======================================")

                    try {

                        val streamResponse = app.get(
                            streamUrl,
                            headers = mapOf(
                                "Referer" to episodeUrl,
                                "Origin" to mainUrl,
                                "User-Agent" to USER_AGENT
                            )
                        )

                        val playerUrls = streamResponse.document
                            .select("iframe[src]")
                            .mapNotNull {
                                it.attr("src")
                                    .trim()
                                    .takeIf { src -> src.isNotBlank() }
                            }
                            .map {
                                resolveUrl(streamUrl, it)
                            }
                            .distinct()

                        if (playerUrls.isEmpty()) {

                            /*
                             * Kalau tiada iframe kedua, cuba terus streamUrl.
                             */
                            tryExtractor(
                                url = streamUrl,
                                referer = episodeUrl,
                                serverName = serverName,
                                processedUrls = processedUrls,
                                subtitleCallback = subtitleCallback,
                                callback = callback
                            )

                            return@forEach
                        }

                        playerUrls.forEach { playerUrl ->

                            println("ANICHIN V2 PLAYER URL [$serverName]: $playerUrl")

                            /*
                             * First pass:
                             * Hantar player utama terus kepada extractor.
                             *
                             * Ini penting untuk VidHidePro, DoodStream,
                             * StreamWish dan server yang extractor boleh baca terus.
                             */
                            tryExtractor(
                                url = playerUrl,
                                referer = streamUrl,
                                serverName = serverName,
                                processedUrls = processedUrls,
                                subtitleCallback = subtitleCallback,
                                callback = callback
                            )

                            /*
                             * Second pass:
                             * Buka player utama dan cari iframe dalaman.
                             *
                             * Ini penting untuk OkRuSSL, Dailymotion
                             * atau wrapper yang ada iframe lagi di dalam.
                             */
                            try {

                                val playerResponse = app.get(
                                    playerUrl,
                                    headers = mapOf(
                                        "Referer" to streamUrl,
                                        "User-Agent" to USER_AGENT
                                    )
                                )

                                val nestedUrls = playerResponse.document
                                    .select("iframe[src]")
                                    .mapNotNull {
                                        it.attr("src")
                                            .trim()
                                            .takeIf { src -> src.isNotBlank() }
                                    }
                                    .map {
                                        resolveUrl(playerUrl, it)
                                    }
                                    .distinct()

                                nestedUrls.forEach { nestedUrl ->

                                    if (nestedUrl == playerUrl) {
                                        return@forEach
                                    }

                                    println(
                                        "ANICHIN V2 NESTED URL " +
                                            "[$serverName]: $nestedUrl"
                                    )

                                    tryExtractor(
                                        url = nestedUrl,
                                        referer = playerUrl,
                                        serverName = serverName,
                                        processedUrls = processedUrls,
                                        subtitleCallback = subtitleCallback,
                                        callback = callback
                                    )
                                }

                            } catch (e: Exception) {
                                println(
                                    "ANICHIN V2 NESTED ERROR " +
                                        "[$serverName]: ${e.message}"
                                )
                            }
                        }

                    } catch (e: Exception) {
                        println(
                            "ANICHIN V2 STREAM ERROR " +
                                "[$serverName]: ${e.message}"
                        )
                    }
                }

            } catch (e: Exception) {
                println(
                    "ANICHIN V2 SERVER ERROR " +
                        "[$serverName]: ${e.message}"
                )
            }
        }

        return true
    }

    private suspend fun tryExtractor(
        url: String,
        referer: String,
        serverName: String,
        processedUrls: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        val cleanUrl = url.trim()

        if (cleanUrl.isBlank()) return

        if (!processedUrls.add(cleanUrl)) {
            println("ANICHIN V2 SKIP DUPLICATE [$serverName]: $cleanUrl")
            return
        }

        try {

            println("ANICHIN V2 LOAD EXTRACTOR [$serverName]: $cleanUrl")

            loadExtractor(
                cleanUrl,
                referer,
                subtitleCallback,
                callback
            )

        } catch (e: Exception) {
            println(
                "ANICHIN V2 EXTRACTOR ERROR " +
                    "[$serverName]: ${e.message}"
            )
        }
    }

    private fun resolveUrl(
        baseUrl: String,
        targetUrl: String
    ): String {

        val cleanTarget = targetUrl.trim()

        if (cleanTarget.startsWith("http://") ||
            cleanTarget.startsWith("https://")
        ) {
            return cleanTarget
        }

        if (cleanTarget.startsWith("//")) {
            return "https:$cleanTarget"
        }

        return try {
            URI(baseUrl)
                .resolve(cleanTarget)
                .toString()
        } catch (e: Exception) {
            fixUrl(cleanTarget)
        }
    }
}
