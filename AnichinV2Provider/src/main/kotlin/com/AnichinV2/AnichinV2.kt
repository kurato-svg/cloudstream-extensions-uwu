package com.AnichinV2

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
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

    private val fastVideoHosts = setOf(
        "ok.ru",
        "odnoklassniki",
        "rumble.com"
    )

    private fun isFastVideoHost(url: String): Boolean {
        return fastVideoHosts.any { host ->
            url.contains(host, ignoreCase = true)
        }
    }

    private fun Element.getImageUrl(): String? {
        val imageUrl = listOf(
            attr("data-src"),
            attr("data-lazy-src"),
            attr("data-original"),
            attr("src")
        ).firstOrNull {
            it.isNotBlank() &&
                !it.startsWith("data:", ignoreCase = true)
        }

        if (imageUrl != null) return imageUrl

        val srcSet = listOf(
            attr("data-srcset"),
            attr("srcset")
        ).firstOrNull { it.isNotBlank() } ?: return null

        return srcSet
            .split(",")
            .lastOrNull()
            ?.trim()
            ?.split(" ")
            ?.firstOrNull()
            ?.takeIf {
                it.isNotBlank() &&
                    !it.startsWith("data:", ignoreCase = true)
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

        val posterUrl = selectFirst("div.bsx > a img")
            ?.getImageUrl()
            ?.let { fixUrlNull(it) }

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
        val searchQuery = URLEncoder.encode(query, "UTF-8")

        for (page in 1..3) {

            val document = app.get(
                "${mainUrl}/page/$page/?s=$searchQuery"
            ).document

            val results = document
                .select("div.listupd > article")
                .mapNotNull { it.toSearchResult() }

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

        val poster = (
            document
                .selectFirst("div.thumb img, div.ime img, img.wp-post-image")
                ?.getImageUrl()
                ?: document
                    .selectFirst("meta[property=og:image]")
                    ?.attr("content")
                    ?.trim()
        ).orEmpty()

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

                    val episodePoster = episodeElement
                        .selectFirst("a img")
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
                        this.posterUrl = episodePoster
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

    private fun cleanFoundUrl(value: String): String? {
        val cleaned = value
            .trim()
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .trimEnd('\\', '"', '\'', ',', ';', ')', ']', '}')

        if (!cleaned.startsWith("http", ignoreCase = true)) return null
        if (cleaned.contains(mainUrl, ignoreCase = true)) return null
        if (cleaned.contains("google-analytics", ignoreCase = true)) return null
        if (cleaned.contains("googletagmanager", ignoreCase = true)) return null
        if (cleaned.contains("facebook.com", ignoreCase = true)) return null
        if (cleaned.contains("twitter.com", ignoreCase = true)) return null
        if (cleaned.contains("x.com", ignoreCase = true)) return null
        if (cleaned.contains(".css", ignoreCase = true)) return null
        if (cleaned.contains(".js", ignoreCase = true)) return null
        if (cleaned.contains(".jpg", ignoreCase = true)) return null
        if (cleaned.contains(".jpeg", ignoreCase = true)) return null
        if (cleaned.contains(".png", ignoreCase = true)) return null
        if (cleaned.contains(".webp", ignoreCase = true)) return null
        if (cleaned.contains(".gif", ignoreCase = true)) return null
        if (cleaned.contains(".ico", ignoreCase = true)) return null
        if (cleaned.contains(".svg", ignoreCase = true)) return null

        return cleaned
    }

    private fun collectAllUrls(document: Document): LinkedHashSet<String> {
        val urls = linkedSetOf<String>()

        document
            .select("iframe[src], a[href], source[src], video[src], embed[src], object[data]")
            .forEach { element ->
                listOf("src", "href", "data").forEach { attrName ->
                    cleanFoundUrl(element.attr(attrName))?.let { urls.add(it) }
                }
            }

        Regex("""https?:\\/\\/[^\s"'<>]+""")
            .findAll(document.html())
            .mapNotNull { cleanFoundUrl(it.value) }
            .forEach { urls.add(it) }

        return urls
    }

    private suspend fun safeLoadExtractor(
        url: String,
        referer: String,
        loadedUrls: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (!loadedUrls.add(url)) return

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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val episodeUrl = fixUrl(data)
        val document = app.get(episodeUrl).document
        val loadedUrls = mutableSetOf<String>()
        val wrapperUrls = linkedSetOf<String>()

        /*
         * Scan 1:
         * Fast scan only.
         * Load OkRu, Odnoklassniki and Rumble first so video can start quickly.
         */
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
            wrapperUrls.add(streamUrl)

            if (isFastVideoHost(streamUrl)) {
                safeLoadExtractor(
                    streamUrl,
                    episodeUrl,
                    loadedUrls,
                    subtitleCallback,
                    callback
                )
                return@optionLoop
            }

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

                    val playerUrl = fixUrl(
                        iframe.attr("src").trim()
                    )

                    if (playerUrl.isBlank()) return@iframeLoop
                    wrapperUrls.add(playerUrl)

                    if (isFastVideoHost(playerUrl)) {
                        safeLoadExtractor(
                            playerUrl,
                            streamUrl,
                            loadedUrls,
                            subtitleCallback,
                            callback
                        )
                        return@iframeLoop
                    }

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

                            val nestedUrl = fixUrl(
                                nested.attr("src").trim()
                            )

                            if (nestedUrl.isBlank()) return@nestedLoop
                            wrapperUrls.add(nestedUrl)

                            if (isFastVideoHost(nestedUrl)) {
                                safeLoadExtractor(
                                    nestedUrl,
                                    playerUrl,
                                    loadedUrls,
                                    subtitleCallback,
                                    callback
                                )
                            }
                        }
                }
        }

        /*
         * Scan 2:
         * Deep scan without host whitelist.
         * Try all external URLs found from wrapper pages.
         * CloudStream extractor decides which URL is valid.
         */
        wrapperUrls.forEach wrapperLoop@ { wrapperUrl ->

            val wrapperDocument = runCatching {
                app.get(
                    wrapperUrl,
                    headers = mapOf(
                        "Referer" to episodeUrl,
                        "Origin" to mainUrl,
                        "User-Agent" to USER_AGENT
                    )
                ).document
            }.getOrNull() ?: return@wrapperLoop

            collectAllUrls(wrapperDocument).forEach { foundUrl ->
                safeLoadExtractor(
                    foundUrl,
                    wrapperUrl,
                    loadedUrls,
                    subtitleCallback,
                    callback
                )
            }
        }

        return true
    }
}
