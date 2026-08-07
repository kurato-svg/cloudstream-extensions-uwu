package com.AnichinV2

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AnichinV2 : MainAPI() {

    override var mainUrl = "https://anichin.moe"
    override var name = "Anichin V2"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime)

    override val mainPage = mainPageOf(
        "anime/?order=update" to "Latest Update",
        "anime/?status=ongoing&order=update" to "Series Ongoing",
        "anime/?status=completed&order=update" to "Series Completed",
        "anime/?status=hiatus&order=update" to "Series Drop/Hiatus",
        "anime/?type=movie&order=update" to "Movie"
    )

    private val supportedVideoHosts = setOf(
        "ok.ru",
        "odnoklassniki",
        "rumble.com",
        "vidguard",
        "streamruby",
        "dood",
        "dailymotion"
    )

    private fun isSupportedVideoHost(url: String) =
        supportedVideoHosts.any { url.contains(it, ignoreCase = true) }

    private fun Element.getImageUrl(): String? =
        sequenceOf("data-src", "data-lazy-src", "data-original", "src")
            .map { attr(it).trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:", true) }

    private fun Document.getPoster(): String =
        selectFirst("div.thumb img, div.ime img, img.wp-post-image")
            ?.getImageUrl()
            ?: selectFirst("meta[property=og:image]")
                ?.attr("content")
                ?.trim()
                .orEmpty()

    private fun Document.getIframeUrls(): List<String> =
        select("iframe[src]")
            .mapNotNull { iframe ->
                iframe.attr("src")
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.let { fixUrl(it) }
            }
            .distinct()

    private suspend fun getDocument(
        url: String,
        referer: String,
        includeOrigin: Boolean = false
    ): Document? = runCatching {
        val headers = mutableMapOf(
            "Referer" to referer,
            "User-Agent" to USER_AGENT
        )
        if (includeOrigin) headers["Origin"] = mainUrl
        app.get(url, headers = headers).document
    }.getOrNull()

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val home = app.get(
            "$mainUrl/${request.data}&page=$page"
        ).document
            .select("div.listupd > article")
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            HomePageList(request.name, home, false),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("div.bsx > a") ?: return null
        val title = anchor.attr("title").trim()
        val href = anchor.attr("href").trim()
        if (title.isBlank() || href.isBlank()) return null

        return newAnimeSearchResponse(title, fixUrl(href), TvType.Anime) {
            posterUrl = anchor.selectFirst("img")
                ?.getImageUrl()
                ?.let { fixUrlNull(it) }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()

        for (page in 1..3) {
            val pageResults = app.get(
                "$mainUrl/page/$page/?s=$query"
            ).document
                .select("div.listupd > article")
                .mapNotNull { it.toSearchResult() }

            if (pageResults.isEmpty()) break
            results.addAll(pageResults)
        }

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(fixUrl(url)).document
        val title = document.selectFirst("h1.entry-title")
            ?.text()
            ?.trim()
            .orEmpty()
        val poster = document.getPoster()
        val description = document.selectFirst("div.entry-content")
            ?.text()
            ?.trim()
        val isMovie = document.selectFirst(".spe")
            ?.text()
            .orEmpty()
            .contains("Movie", ignoreCase = true)

        if (isMovie) {
            val movieUrl = document.selectFirst(".eplister li > a")
                ?.attr("href")
                ?.let { fixUrl(it) }
                ?: url

            return newMovieLoadResponse(
                title,
                movieUrl,
                TvType.Movie,
                movieUrl
            ) {
                posterUrl = fixUrlNull(poster)
                plot = description
            }
        }

        val episodes = document.select(".eplister li")
            .map { element ->
                val episodeUrl = fixUrl(
                    element.selectFirst("a")
                        ?.attr("href")
                        .orEmpty()
                )
                val episodeTitle = element.selectFirst(".epl-title")
                    ?.text()
                    ?.trim()
                    .orEmpty()
                val episodeSub = element.selectFirst(".epl-sub span")
                    ?.text()
                    ?.trim()
                    .orEmpty()
                val episodeDate = element.selectFirst(".epl-date")
                    ?.text()
                    ?.trim()
                    .orEmpty()
                val episodePoster = element.selectFirst("a img")
                    ?.getImageUrl()
                    ?.let { fixUrlNull(it) }
                    ?: fixUrlNull(poster)

                val cleanTitle = episodeTitle
                    .replace(
                        Regex(
                            "Episode\\s*\\d+\\s*Subtitle Indonesia",
                            RegexOption.IGNORE_CASE
                        ),
                        ""
                    )
                    .replace("Subtitle Indonesia", "", ignoreCase = true)
                    .trim()

                newEpisode(episodeUrl) {
    name = "- $cleanTitle $episodeSub Indonesia".trim()
    posterUrl = episodePoster
                }
            }
            .reversed()

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.Anime,
            episodes
        ) {
            posterUrl = fixUrlNull(poster)
            plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeUrl = fixUrl(data)
        val loadedUrls = mutableSetOf<String>()

        app.get(episodeUrl).document
            .select(".mobius option")
            .forEach optionLoop@ { option ->
                val encoded = option.attr("value").trim()
                if (encoded.isBlank()) return@optionLoop

                val streamUrl = runCatching {
                    Jsoup.parse(base64Decode(encoded))
                        .selectFirst("iframe[src]")
                        ?.attr("src")
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?.let { fixUrl(it) }
                }.getOrNull() ?: return@optionLoop

                val candidates = linkedMapOf<String, String>()

                if (isSupportedVideoHost(streamUrl)) {
                    candidates[streamUrl] = episodeUrl
                } else {
                    val streamDocument = getDocument(
                        streamUrl,
                        episodeUrl,
                        includeOrigin = true
                    ) ?: return@optionLoop

                    streamDocument.getIframeUrls().forEach playerLoop@ { playerUrl ->
                        if (isSupportedVideoHost(playerUrl)) {
                            candidates[playerUrl] = streamUrl
                            return@playerLoop
                        }

                        val nestedDocument = getDocument(
                            playerUrl,
                            streamUrl
                        ) ?: return@playerLoop

                        nestedDocument.getIframeUrls()
                            .filter(::isSupportedVideoHost)
                            .forEach { nestedUrl ->
                                candidates[nestedUrl] = playerUrl
                            }
                    }
                }

                candidates.forEach candidateLoop@ { (url, referer) ->
                    if (!loadedUrls.add(url)) return@candidateLoop

                    runCatching {
                        loadExtractor(
                            url,
                            referer,
                            subtitleCallback,
                            callback
                        )
                    }
                }
            }

        return true
    }
}
