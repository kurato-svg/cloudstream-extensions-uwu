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

    private val supportedVideoHosts = listOf(
    "ok.ru",
    "odnoklassniki",
    "rumble.com",
    "vidguard",
    "streamruby",
    "dood",
    "dailymotion"
)

    private fun isSupportedVideoHost(url: String): Boolean {
        return supportedVideoHosts.any { host ->
            url.contains(host, ignoreCase = true)
        }
    }

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

        val title = select("div.bsx > a")
            .attr("title")
            .trim()

        val href = fixUrl(
            select("div.bsx > a")
                .attr("href")
        )

        val posterUrl = fixUrlNull(
            select("div.bsx > a img")
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

        for (page in 1..3) {

            val document = app.get(
                "${mainUrl}/page/$page/?s=$query"
            ).document

            val results = document
                .select("div.listupd > article")
                .map { it.toSearchResult() }

            if (results.isEmpty()) break

            searchResponse.addAll(results)
        }

        return searchResponse.distinctBy { it.url }
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

        val tvType = if (type.contains("Movie", true)) {
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
                .map { episodeElement ->

                    val link = fixUrl(
                        episodeElement
                            .selectFirst("a")
                            ?.attr("href")
                            .orEmpty()
                    )

                    val episodeTitle = episodeElement
                        .selectFirst(".epl-title")
                        ?.text()
                        ?.trim()
                        .orEmpty()

                    val episodeSub = episodeElement
                        .selectFirst(".epl-sub span")
                        ?.text()
                        ?.trim()
                        .orEmpty()

                    val episodeDate = episodeElement
                        .selectFirst(".epl-date")
                        ?.text()
                        ?.trim()
                        .orEmpty()

                    val cleanTitle = episodeTitle
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

                    val episodeName =
                        "- $cleanTitle $episodeSub Indonesia".trim()

                    val episodeDescription =
                        episodeDate
                            .takeIf { it.isNotEmpty() }
                            ?.let { "Rilis: $it" }

                    newEpisode(link) {
                        this.name = episodeName
                        this.posterUrl = fixUrlNull(poster)
                        this.description = episodeDescription
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

            val movieHref = document
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

        val episodeUrl = fixUrl(data)
        val document = app.get(episodeUrl).document
        val loadedUrls = mutableSetOf<String>()

        document.select(".mobius option").forEach optionLoop@ { option ->

            val encodedValue = option.attr("value").trim()
            if (encodedValue.isBlank()) return@optionLoop

            val decodedDocument = runCatching {
                Jsoup.parse(base64Decode(encodedValue))
            }.getOrNull() ?: return@optionLoop

            val firstIframe = decodedDocument
                .selectFirst("iframe[src]")
                ?.attr("src")
                ?.trim()
                .orEmpty()

            if (firstIframe.isBlank()) return@optionLoop

            val streamUrl = fixUrl(firstIframe)

            /*
             * Store each extractor URL together with its correct referer.
             * LinkedHashMap keeps the website order and removes duplicates.
             */
            val candidates = linkedMapOf<String, String>()

            if (isSupportedVideoHost(streamUrl)) {
                candidates[streamUrl] = episodeUrl
            } else {
                val streamDocument = runCatching {
                    app.get(
                        streamUrl,
                        headers = mapOf(
                            "Referer" to episodeUrl,
                            "Origin" to mainUrl,
                            "User-Agent" to USER_AGENT
                        )
                    ).document
                }.getOrNull() ?: return@optionLoop

                streamDocument
                    .select("iframe[src]")
                    .forEach iframeLoop@ { iframe ->

                        val iframeValue = iframe.attr("src").trim()
                        if (iframeValue.isBlank()) return@iframeLoop

                        val playerUrl = fixUrl(iframeValue)

                        if (isSupportedVideoHost(playerUrl)) {
                            candidates[playerUrl] = streamUrl
                            return@iframeLoop
                        }

                        /*
                         * Only open one wrapper level. This keeps loading fast
                         * while still finding nested OkRu, Rumble, VidGuard,
                         * StreamRuby and Dood embeds.
                         */
                        val nestedDocument = runCatching {
                            app.get(
                                playerUrl,
                                headers = mapOf(
                                    "Referer" to streamUrl,
                                    "User-Agent" to USER_AGENT
                                )
                            ).document
                        }.getOrNull() ?: return@iframeLoop

                        nestedDocument
                            .select("iframe[src]")
                            .forEach nestedLoop@ { nested ->

                                val nestedValue =
                                    nested.attr("src").trim()

                                if (nestedValue.isBlank()) {
                                    return@nestedLoop
                                }

                                val nestedUrl = fixUrl(nestedValue)

                                if (isSupportedVideoHost(nestedUrl)) {
                                    candidates[nestedUrl] = playerUrl
                                }
                            }
                    }
            }

            candidates.forEach candidateLoop@ { (url, referer) ->

                if (!loadedUrls.add(url)) {
                    return@candidateLoop
                }

                runCatching {
    loadExtractor(
        url,
        referer,
        subtitleCallback,
        callback
    )
}.onFailure {
    // ignore failed extractor
}
            }
        }

        return true
    }
}
